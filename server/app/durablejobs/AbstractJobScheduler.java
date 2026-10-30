package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import models.JobType;
import models.PersistedDurableJobModel;
import play.Logger;
import play.Logger.ALogger;
import play.db.Database;
import services.PersistedDurableJobRepository;

/**
 * Schedules future {@link DurableJob} records in {@code persisted_durable_jobs}.
 *
 * <p>Replicates CiviForm's {@code AbstractJobScheduler}, adapted from Ebean to JDBC with {@link
 * Connection#TRANSACTION_SERIALIZABLE} isolation to prevent duplicate recurring rows across servers.
 */
public abstract class AbstractJobScheduler {

  private static final ALogger log = Logger.of(AbstractJobScheduler.class);
  private static final int SCHEDULER_ATTEMPTS = 5;
  /** PostgreSQL SQLState code for {@code serialization_failure}. */
  static final String SERIALIZATION_FAILURE_SQL_STATE = "40001";

  private final Clock clock;
  private final Database db;
  private final DurableJobRegistry durableJobRegistry;
  private final PersistedDurableJobRepository persistedDurableJobRepository;

  protected AbstractJobScheduler(
      Clock clock,
      Database db,
      DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository) {
    this.clock = checkNotNull(clock);
    this.db = checkNotNull(db);
    this.durableJobRegistry = checkNotNull(durableJobRegistry);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
  }

  /** Returns the {@link JobType}s this scheduler is allowed to schedule. */
  protected abstract ImmutableList<JobType> allowedJobTypes();

  /** Returns an existing scheduled job from the database inside {@code conn}, if one exists. */
  protected abstract Optional<PersistedDurableJobModel> findScheduledJob(
      Connection conn, DurableJobRegistry.RegisteredJob registeredJob) throws SQLException;

  /**
   * Ensures each registered recurring job in {@link DurableJobRegistry} has an uncompleted {@link
   * PersistedDurableJobModel} scheduled in the database, inserting one if absent.
   *
   * <p>{@code synchronized} to prevent overlapping scheduling sweeps on the same server instance.
   */
  public synchronized void scheduleJobs() {
    for (DurableJobRegistry.RegisteredJob registeredJob : durableJobRegistry.getRecurringJobs()) {
      if (registeredJob.getRecurringJobExecutionTimeResolver().isEmpty()) {
        log.error(
            "JobScheduler_SchedulingError No RecurringJobExecutionTimeResolver registered with {}",
            registeredJob.getJobName());
        continue;
      }

      if (!allowedJobTypes().contains(registeredJob.getJobType())) {
        continue;
      }

      Instant executionTime =
          registeredJob.getRecurringJobExecutionTimeResolver().get().resolveExecutionTime(clock);

      PersistedDurableJobModel newJob =
          new PersistedDurableJobModel(
              registeredJob.getJobName().getJobNameString(),
              registeredJob.getJobType(),
              executionTime);
      try {
        tryScheduleJob(registeredJob, newJob, SCHEDULER_ATTEMPTS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  protected synchronized void tryScheduleJob(
      DurableJobRegistry.RegisteredJob registeredJob,
      PersistedDurableJobModel newJob,
      int remainingAttempts)
      throws InterruptedException {
    try (Connection conn = db.getConnection(false)) {
      int previousIsolation = conn.getTransactionIsolation();
      conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      try {
        // Re-check inside the SERIALIZABLE transaction so concurrent servers cannot both insert.
        if (findScheduledJob(conn, registeredJob).isEmpty()) {
          persistedDurableJobRepository.save(conn, newJob);
          conn.commit();
        } else {
          conn.rollback();
        }
      } catch (SQLException e) {
        try {
          conn.rollback();
        } catch (SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        throw e;
      } finally {
        try {
          conn.setTransactionIsolation(previousIsolation);
        } catch (SQLException ignored) {
          // Best-effort restore before returning pooled connection.
        }
      }
    } catch (SQLException e) {
      if (SERIALIZATION_FAILURE_SQL_STATE.equals(e.getSQLState())) {
        if (remainingAttempts > 0) {
          log.warn(
              "JobScheduler_SchedulingError Serialization failure scheduling {} with {} remaining"
                  + " attempts",
              newJob.getJobName(),
              remainingAttempts);
          Thread.sleep(ThreadLocalRandom.current().nextInt(100));
          tryScheduleJob(registeredJob, newJob, remainingAttempts - 1);
        } else {
          log.error(
              "JobScheduler_SchedulingError Serialization failure scheduling {}, no attempts"
                  + " remaining!",
              newJob.getJobName());
        }
      } else {
        log.error(
            "JobScheduler_DatabaseError Failed scheduling {}: {}",
            newJob.getJobName(),
            e.getMessage(),
            e);
      }
    }
  }
}
