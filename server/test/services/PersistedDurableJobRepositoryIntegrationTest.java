package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import play.Application;
import play.db.Database;
import play.inject.guice.GuiceApplicationBuilder;
import play.test.Helpers;

/**
 * Integration tests for {@link PersistedDurableJobRepository} against the Docker Compose Postgres.
 */
public class PersistedDurableJobRepositoryIntegrationTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T15:00:00Z"), ZoneOffset.UTC);

  private static Application app;
  private PersistedDurableJobRepository repo;
  private final List<Long> createdIds = new ArrayList<>();

  @BeforeClass
  public static void startApp() {
    assumeTrue(
        "Skipping: Postgres not reachable at db:5432 or localhost:5432", isPostgresReachable());
    app = new GuiceApplicationBuilder().configure("durable_jobs.enabled", false).build();
    Helpers.start(app);
  }

  @AfterClass
  public static void stopApp() {
    if (app != null) {
      Helpers.stop(app);
      app = null;
    }
  }

  @Before
  public void setUp() {
    assumeTrue("Skipping: no Postgres", app != null);
    Database db = app.injector().instanceOf(Database.class);
    new SandboxRepository(db).ensureSchemaInitialized();
    repo = new PersistedDurableJobRepository(db, FIXED_CLOCK);
  }

  @After
  public void cleanup() {
    if (app == null || createdIds.isEmpty()) {
      return;
    }
    Database db = app.injector().instanceOf(Database.class);
    db.withConnection(
        conn -> {
          for (Long id : createdIds) {
            repo.delete(conn, id);
          }
          return null;
        });
    createdIds.clear();
  }

  @Test
  public void saveAndFindById_roundTripsOnDemandDurableJob() {
    Instant executionTime = FIXED_CLOCK.instant().truncatedTo(ChronoUnit.MICROS);
    PersistedDurableJobModel saved =
        repo.save(new PersistedDurableJobModel("OLD_JOB_CLEANUP", JobType.RECURRING, executionTime));

    assertThat(saved.getId()).isNotNull();
    createdIds.add(saved.getId());

    Optional<PersistedDurableJobModel> loaded = repo.findById(saved.getId());
    assertThat(loaded).isPresent();
    assertThat(loaded.get().getJobName()).isEqualTo("OLD_JOB_CLEANUP");
    assertThat(loaded.get().getJobType()).isEqualTo(JobType.RECURRING);
    assertThat(loaded.get().getExecutionTime()).isEqualTo(executionTime);
    assertThat(loaded.get().getRemainingAttempts()).isEqualTo(3);
    assertThat(loaded.get().getSuccessTime()).isEmpty();

    saved.setSuccessTime(executionTime);
    saved.decrementRemainingAttempts();
    repo.save(saved);

    Optional<PersistedDurableJobModel> updated = repo.findById(saved.getId());
    assertThat(updated).isPresent();
    assertThat(updated.get().getSuccessTime()).contains(executionTime);
    assertThat(updated.get().getRemainingAttempts()).isEqualTo(2);
  }

  @Test
  public void findById_nonExistentId_returnsEmpty() {
    assertThat(repo.findById(-999999L)).isEmpty();
  }

  private static boolean isPostgresReachable() {
    return isReachable("db", 5432) || isReachable("localhost", 5432);
  }

  private static boolean isReachable(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 1000);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
