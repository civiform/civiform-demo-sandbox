package durablejobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.Before;
import org.junit.Test;
import play.db.Database;
import services.PersistedDurableJobRepository;
import services.SlackAlertClient;

/** Unit tests for {@link RecurringDurableJobRunner}. */
public class RecurringDurableJobRunnerTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T02:05:00Z"), ZoneOffset.UTC);

  private Database db;
  private Connection conn;
  private DurableJobExecutionContext executionContext;
  private DurableJobRegistry registry;
  private PersistedDurableJobRepository repository;
  private SlackAlertClient slackAlertClient;
  private Config config;

  @Before
  public void setUp() throws Exception {
    db = mock(Database.class);
    conn = mock(Connection.class);
    when(db.getConnection(false)).thenReturn(conn);

    executionContext = mock(DurableJobExecutionContext.class);
    when(executionContext.current()).thenReturn(Executors.newSingleThreadExecutor());

    registry = new DurableJobRegistry();
    repository = mock(PersistedDurableJobRepository.class);
    when(repository.save(eq(conn), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    slackAlertClient = mock(SlackAlertClient.class);

    config =
        ConfigFactory.parseMap(
            Map.of(
                "app.baseUrl", "https://sandbox.civiform.dev",
                "durable_jobs.poll_interval_seconds", 0,
                "durable_jobs.job_timeout_minutes", 0));
  }

  private RecurringDurableJobRunner newRunner() {
    return new RecurringDurableJobRunner(
        config, db, FIXED_CLOCK, executionContext, registry, repository, slackAlertClient);
  }

  @Test
  public void runJobs_succeedingJobSetsSuccessTimeAndCommits() throws Exception {
    AtomicInteger runs = new AtomicInteger(0);
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted ->
            new DurableJob() {
              @Override
              public PersistedDurableJobModel getPersistedDurableJob() {
                return persisted;
              }

              @Override
              public void run() {
                runs.incrementAndGet();
              }
            },
        new RecurringJobExecutionTimeResolvers.Daily2Am());

    PersistedDurableJobModel job =
        PersistedDurableJobModel.builder()
            .id(42L)
            .jobName("TEST")
            .jobType(JobType.RECURRING)
            .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
            .remainingAttempts(3)
            .build();
    when(repository.getRecurringJobForExecution(conn))
        .thenReturn(Optional.of(job))
        .thenReturn(Optional.empty());

    newRunner().runJobs();

    assertThat(runs.get()).isEqualTo(1);
    assertThat(job.getRemainingAttempts()).isEqualTo(2);
    assertThat(job.getSuccessTime()).contains(FIXED_CLOCK.instant());
    assertThat(job.getErrorMessage()).isEmpty();
    verify(repository, times(2)).save(conn, job);
    verify(conn).commit();
    verify(slackAlertClient, never()).sendJobFailureAlert(any(), any());
  }

  @Test
  public void runJobs_failingJobWithRemainingAttemptsAdvancesExecutionTimeWithoutSlackAlert()
      throws Exception {
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted ->
            new DurableJob() {
              @Override
              public PersistedDurableJobModel getPersistedDurableJob() {
                return persisted;
              }

              @Override
              public void run() {
                throw new RuntimeException("Metadata DB timeout");
              }
            },
        new RecurringJobExecutionTimeResolvers.Daily2Am());

    PersistedDurableJobModel job =
        PersistedDurableJobModel.builder()
            .id(7L)
            .jobName("TEST")
            .jobType(JobType.RECURRING)
            .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
            .remainingAttempts(3)
            .build();
    when(repository.getRecurringJobForExecution(conn))
        .thenReturn(Optional.of(job))
        .thenReturn(Optional.empty());

    newRunner().runJobs();

    assertThat(job.getRemainingAttempts()).isEqualTo(2);
    assertThat(job.getSuccessTime()).isEmpty();
    assertThat(job.getExecutionTime()).isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));
    assertThat(job.getErrorMessage()).isPresent();
    assertThat(job.getErrorMessage().get()).contains("Metadata DB timeout");
    verify(slackAlertClient, never()).sendJobFailureAlert(any(), any());
  }

  @Test
  public void runJobs_finalFailureAlertsSlackChannel() throws Exception {
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted ->
            new DurableJob() {
              @Override
              public PersistedDurableJobModel getPersistedDurableJob() {
                return persisted;
              }

              @Override
              public void run() {
                throw new RuntimeException("Fatal job crash");
              }
            },
        new RecurringJobExecutionTimeResolvers.Daily2Am());

    PersistedDurableJobModel job =
        PersistedDurableJobModel.builder()
            .id(99L)
            .jobName("TEST")
            .jobType(JobType.RECURRING)
            .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
            .remainingAttempts(1)
            .build();
    when(repository.getRecurringJobForExecution(conn))
        .thenReturn(Optional.of(job))
        .thenReturn(Optional.empty());

    newRunner().runJobs();

    assertThat(job.getRemainingAttempts()).isEqualTo(0);
    assertThat(job.hasFailedWithNoRemainingAttempts()).isTrue();
    verify(slackAlertClient).sendJobFailureAlert(eq(job), eq("sandbox.civiform.dev"));
  }

  @Test
  public void runJobs_unregisteredJobIsDeletedFromDatabase() throws Exception {
    PersistedDurableJobModel unknownJob =
        PersistedDurableJobModel.builder()
            .id(55L)
            .jobName("REMOVED_LEGACY_JOB")
            .jobType(JobType.RECURRING)
            .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
            .remainingAttempts(3)
            .build();
    when(repository.getRecurringJobForExecution(conn))
        .thenReturn(Optional.of(unknownJob))
        .thenReturn(Optional.empty());
    when(repository.delete(conn, 55L)).thenReturn(true);

    newRunner().runJobs();

    verify(repository).delete(conn, 55L);
    verify(slackAlertClient, never()).sendJobFailureAlert(any(), any());
  }
}
