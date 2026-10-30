package durablejobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import play.db.Database;
import services.PersistedDurableJobRepository;

/** Unit tests for {@link RecurringJobScheduler}. */
public class RecurringJobSchedulerTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);

  private Database db;
  private Connection conn;
  private PersistedDurableJobRepository repository;
  private DurableJobRegistry registry;

  @Before
  public void setUp() throws Exception {
    db = mock(Database.class);
    conn = mock(Connection.class);
    when(db.getConnection(false)).thenReturn(conn);
    when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    repository = mock(PersistedDurableJobRepository.class);
    registry = new DurableJobRegistry();
  }

  @Test
  public void scheduleJobs_insertsNewJobWhenNoneScheduled() throws Exception {
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted -> mock(DurableJob.class),
        new RecurringJobExecutionTimeResolvers.Daily2Am());
    when(repository.findScheduledRecurringJob(eq(conn), eq("TEST")))
        .thenReturn(Optional.empty());

    RecurringJobScheduler scheduler =
        new RecurringJobScheduler(FIXED_CLOCK, db, registry, repository);
    scheduler.scheduleJobs();

    ArgumentCaptor<PersistedDurableJobModel> saved =
        ArgumentCaptor.forClass(PersistedDurableJobModel.class);
    verify(conn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    verify(repository).save(eq(conn), saved.capture());
    verify(conn).commit();

    assertThat(saved.getValue().getJobName()).isEqualTo("TEST");
    assertThat(saved.getValue().getJobType()).isEqualTo(JobType.RECURRING);
    assertThat(saved.getValue().getExecutionTime())
        .isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));
    assertThat(saved.getValue().getRemainingAttempts()).isEqualTo(3);
  }

  @Test
  public void scheduleJobs_skipsWhenJobAlreadyScheduled() throws Exception {
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted -> mock(DurableJob.class),
        new RecurringJobExecutionTimeResolvers.Daily2Am());
    PersistedDurableJobModel existing =
        new PersistedDurableJobModel(
            "TEST",
            JobType.RECURRING,
            Instant.parse("2026-08-16T02:00:00Z"));
    when(repository.findScheduledRecurringJob(eq(conn), eq("TEST")))
        .thenReturn(Optional.of(existing));

    RecurringJobScheduler scheduler =
        new RecurringJobScheduler(FIXED_CLOCK, db, registry, repository);
    scheduler.scheduleJobs();

    verify(repository, never()).save(any(), any());
    verify(conn).rollback();
  }

  @Test
  public void scheduleJobs_retriesOnPostgresSerializationFailure() throws Exception {
    registry.register(
        DurableJobName.TEST,
        JobType.RECURRING,
        persisted -> mock(DurableJob.class),
        new RecurringJobExecutionTimeResolvers.Daily2Am());

    SQLException serializationConflict =
        new SQLException("could not serialize access", "40001");
    when(repository.findScheduledRecurringJob(eq(conn), eq("TEST")))
        .thenThrow(serializationConflict)
        .thenReturn(Optional.empty());

    RecurringJobScheduler scheduler =
        new RecurringJobScheduler(FIXED_CLOCK, db, registry, repository);
    scheduler.scheduleJobs();

    verify(repository, times(2))
        .findScheduledRecurringJob(eq(conn), eq("TEST"));
    verify(repository, times(1)).save(eq(conn), any());
  }
}
