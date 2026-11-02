package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.typesafe.config.Config;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import models.PersistedDurableJobModel;
import org.apache.commons.lang3.exception.ExceptionUtils;
import play.Logger;
import play.Logger.ALogger;
import play.db.Database;
import services.PersistedDurableJobRepository;
import services.SlackAlertClient;

/**
 * Executes persisted {@link DurableJob}s whose scheduled execution time has arrived.
 *
 * <p>Replicates CiviForm's {@code AbstractDurableJobRunner}, adapted to Play's JDBC pool and {@link
 * SlackAlertClient} notifications to {@code #civiform-sre-alerts}.
 */
public abstract class AbstractDurableJobRunner {

  private static final ALogger log = Logger.of(AbstractDurableJobRunner.class);

  private final String hostName;
  private final Database db;
  private final Clock clock;
  private final DurableJobExecutionContext durableJobExecutionContext;
  private final DurableJobRegistry durableJobRegistry;
  private final PersistedDurableJobRepository persistedDurableJobRepository;
  private final SlackAlertClient slackAlertClient;
  private final int jobTimeoutMinutes;

  protected AbstractDurableJobRunner(
      Config config,
      Database db,
      Clock clock,
      DurableJobExecutionContext durableJobExecutionContext,
      DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository,
      SlackAlertClient slackAlertClient) {
    checkNotNull(config);
    String baseUrl = config.hasPath("app.baseUrl") ? config.getString("app.baseUrl") : "localhost";
    this.hostName = baseUrl.replace("https://", "").replace("http://", "");
    this.db = checkNotNull(db);
    this.clock = checkNotNull(clock);
    this.durableJobExecutionContext = checkNotNull(durableJobExecutionContext);
    this.durableJobRegistry = checkNotNull(durableJobRegistry);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
    this.slackAlertClient = checkNotNull(slackAlertClient);
    this.jobTimeoutMinutes = config.getInt("durable_jobs.job_timeout_minutes");
  }

  /** Claims the next ready job inside {@code conn}'s transaction, or empty if none is ready. */
  protected abstract Optional<PersistedDurableJobModel> getJobForExecution(Connection conn)
      throws SQLException;

  /** Determines whether {@code maybeJobToRun} is eligible to execute before {@code stopTime}. */
  protected abstract boolean canRun(
      Optional<PersistedDurableJobModel> maybeJobToRun, Instant stopTime);

  /** Resolves the deadline at which this runner invocation must yield until the next poll tick. */
  protected abstract Instant resolveStopTime();

  /**
   * Queries for ready durable jobs and executes them sequentially until none remain or the poll
   * interval window elapses.
   *
   * <p>{@code synchronized} to prevent overlapping runner invocations inside the same JVM.
   */
  public synchronized void runJobs() {
    log.info("JobRunner_Start thread_ID={}", Thread.currentThread().threadId());
    Instant stopTime = resolveStopTime();

    while (clock.instant().isBefore(stopTime)) {
      PersistedDurableJobModel completedOrFailedJob = null;
      try (Connection conn = db.getConnection(false)) {
        try {
          Optional<PersistedDurableJobModel> maybeJobToRun = getJobForExecution(conn);
          if (canRun(maybeJobToRun, stopTime)) {
            PersistedDurableJobModel jobToRun = maybeJobToRun.get();
            runJob(conn, jobToRun);
            conn.commit();
            completedOrFailedJob = jobToRun;
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
        }
      } catch (SQLException e) {
        log.error("JobRunner_DatabaseError error_message={}", e.getMessage(), e);
        break;
      }

      if (completedOrFailedJob == null) {
        break;
      }
      notifyUponFinalFailure(completedOrFailedJob);
    }

    log.info("JobRunner_Stop thread_ID={}", Thread.currentThread().threadId());
  }

  private void notifyUponFinalFailure(PersistedDurableJobModel job) {
    if (!job.hasFailedWithNoRemainingAttempts()) {
      return;
    }
    slackAlertClient.sendJobFailureAlert(job, hostName);
  }

  private void runJob(Connection conn, PersistedDurableJobModel persistedDurableJob)
      throws SQLException {
    Instant startTime = clock.instant();
    log.info(
        "JobRunner_ExecutingJob thread_ID={}, job_name=\"{}\", job_ID={}",
        Thread.currentThread().threadId(),
        persistedDurableJob.getJobName(),
        persistedDurableJob.getId());

    DurableJobRegistry.RegisteredJob registeredJob = null;
    try {
      persistedDurableJob.decrementRemainingAttempts();
      persistedDurableJobRepository.save(conn, persistedDurableJob);

      Optional<DurableJobName> optionalJobName =
          DurableJobName.optionalValueOf(persistedDurableJob.getJobName());
      if (optionalJobName.isEmpty()) {
        throw new JobNotFoundException(
            String.format(
                "Job name \"%s\" not found in DurableJobName", persistedDurableJob.getJobName()));
      }

      registeredJob = durableJobRegistry.get(optionalJobName.get());
      runJobWithTimeout(registeredJob.getFactory().create(persistedDurableJob));

      persistedDurableJob.setSuccessTime(clock.instant());
      persistedDurableJobRepository.save(conn, persistedDurableJob);

      log.info(
          "JobRunner_JobSucceeded job_name=\"{}\", job_ID={}, duration_s={}",
          persistedDurableJob.getJobName(),
          persistedDurableJob.getId(),
          getJobDurationInSeconds(startTime));
    } catch (JobNotFoundException e) {
      // Matches CiviForm: if a job was intentionally removed from the registry, purge it from DB.
      if (persistedDurableJob.getId() != null
          && persistedDurableJobRepository.delete(conn, persistedDurableJob.getId())) {
        log.info(
            "Job was not found in the registry and was deleted from the db. job_name=\"{}\"",
            persistedDurableJob.getJobName());
      } else {
        String msg =
            String.format(
                "Job was not found in the registry and there was an error deleting the job. Error:"
                    + " %s job_name=\"%s\", job_ID=%d, attempts_remaining=%d, duration_s=%f,"
                    + " message: %s",
                e.getClass().getSimpleName(),
                persistedDurableJob.getJobName(),
                persistedDurableJob.getId(),
                persistedDurableJob.getRemainingAttempts(),
                getJobDurationInSeconds(startTime),
                e.getMessage());
        log.error(msg);
        persistedDurableJob.appendErrorMessage(msg);
        persistedDurableJobRepository.save(conn, persistedDurableJob);
      }
    } catch (IllegalArgumentException | CancellationException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      String msg =
          String.format(
              "JobRunner_JobFailed %s job_name=\"%s\", job_ID=%d, attempts_remaining=%d,"
                  + " duration_s=%f, message: %s",
              e.getClass().getSimpleName(),
              persistedDurableJob.getJobName(),
              persistedDurableJob.getId(),
              persistedDurableJob.getRemainingAttempts(),
              getJobDurationInSeconds(startTime),
              e.getMessage());
      log.error(msg);
      advanceExecutionTimeForRetry(persistedDurableJob, registeredJob);
      persistedDurableJob.appendErrorMessage(msg);
      persistedDurableJobRepository.save(conn, persistedDurableJob);
    } catch (TimeoutException e) {
      String msg =
          String.format(
              "JobRunner_JobTimeout job_name=\"%s\", job_ID=%d, attempts_remaining=%d,"
                  + " duration_s=%f",
              persistedDurableJob.getJobName(),
              persistedDurableJob.getId(),
              persistedDurableJob.getRemainingAttempts(),
              getJobDurationInSeconds(startTime));
      log.error(msg);
      advanceExecutionTimeForRetry(persistedDurableJob, registeredJob);
      persistedDurableJob.appendErrorMessage(msg);
      persistedDurableJobRepository.save(conn, persistedDurableJob);
    } catch (ExecutionException e) {
      String msg =
          String.format(
              "JobRunner_JobFailed ExecutionException job_name=\"%s\", job_ID=%d,"
                  + " attempts_remaining=%d, duration_s=%f, error_message=%s, trace=%s",
              persistedDurableJob.getJobName(),
              persistedDurableJob.getId(),
              persistedDurableJob.getRemainingAttempts(),
              getJobDurationInSeconds(startTime),
              e.getMessage(),
              ExceptionUtils.getStackTrace(e));
      log.error(msg);
      advanceExecutionTimeForRetry(persistedDurableJob, registeredJob);
      persistedDurableJob.appendErrorMessage(msg);
      persistedDurableJobRepository.save(conn, persistedDurableJob);
    }
  }

  private void advanceExecutionTimeForRetry(
      PersistedDurableJobModel persistedDurableJob, DurableJobRegistry.RegisteredJob registeredJob) {
    if (persistedDurableJob.getRemainingAttempts() <= 0 || registeredJob == null) {
      return;
    }
    registeredJob
        .getRecurringJobExecutionTimeResolver()
        .ifPresent(resolver -> persistedDurableJob.setExecutionTime(resolver.resolveExecutionTime(clock)));
  }

  private synchronized void runJobWithTimeout(DurableJob jobToRun)
      throws ExecutionException, InterruptedException, TimeoutException {
    CompletableFuture<Void> future =
        CompletableFuture.runAsync(jobToRun::run, durableJobExecutionContext.current());

    try {
      // Matches CiviForm: setting job_timeout_minutes to 0 in tests bounds timeout to 2500ms.
      if (jobTimeoutMinutes == 0) {
        future.get(2500, TimeUnit.MILLISECONDS);
        return;
      }
      future.get(jobTimeoutMinutes, TimeUnit.MINUTES);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw e;
    }
  }

  private double getJobDurationInSeconds(Instant startTime) {
    return ((double) Duration.between(startTime, clock.instant()).toMillis()) / 1000.0;
  }
}
