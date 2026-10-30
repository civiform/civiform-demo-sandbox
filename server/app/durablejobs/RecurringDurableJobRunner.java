package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.typesafe.config.Config;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.PersistedDurableJobModel;
import play.db.Database;
import services.PersistedDurableJobRepository;
import services.SlackAlertClient;

/**
 * Executes recurring {@link DurableJob}s when their scheduled {@code execution_time} arrives.
 *
 * <p>Replicates CiviForm's {@code RecurringDurableJobRunner}.
 */
@Singleton
public final class RecurringDurableJobRunner extends AbstractDurableJobRunner {

  private final PersistedDurableJobRepository persistedDurableJobRepository;
  private final Clock clock;
  private final int runnerLifespanSeconds;

  @Inject
  public RecurringDurableJobRunner(
      Config config,
      Database db,
      Clock clock,
      DurableJobExecutionContext durableJobExecutionContext,
      @RecurringJobsProviderName DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository,
      SlackAlertClient slackAlertClient) {
    super(
        config,
        db,
        clock,
        durableJobExecutionContext,
        durableJobRegistry,
        persistedDurableJobRepository,
        slackAlertClient);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
    this.clock = checkNotNull(clock);
    this.runnerLifespanSeconds = config.getInt("durable_jobs.poll_interval_seconds");
  }

  @Override
  protected synchronized Optional<PersistedDurableJobModel> getJobForExecution(Connection conn)
      throws SQLException {
    return persistedDurableJobRepository.getRecurringJobForExecution(conn);
  }

  @Override
  protected synchronized boolean canRun(
      Optional<PersistedDurableJobModel> maybeJobToRun, Instant stopTime) {
    return maybeJobToRun.isPresent() && clock.instant().isBefore(stopTime);
  }

  @Override
  protected synchronized Instant resolveStopTime() {
    // Setting poll_interval_seconds to 0 in tests bounds runner execution window to 5s.
    if (runnerLifespanSeconds == 0) {
      return clock.instant().plus(5000, ChronoUnit.MILLIS);
    }
    return clock.instant().plus(runnerLifespanSeconds, ChronoUnit.SECONDS);
  }
}
