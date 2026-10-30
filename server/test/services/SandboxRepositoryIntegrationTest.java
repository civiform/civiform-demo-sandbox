package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import models.SandboxInstance;
import models.SandboxStatus;
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
 * Integration tests for {@link SandboxRepository} against the real Docker Compose Postgres.
 *
 * <p>Unlike {@link SandboxRepositoryTest} (which mocks JDBC), these tests exercise the full
 * round-trip: Java → JDBC driver → Postgres wire protocol → real schema. They catch the class
 * of bug we hit in #35 and #42: column-doesn't-exist, schema drift, bad SQL that Postgres
 * rejects but mocks silently accept.
 *
 * <p><b>Prerequisites:</b> The Docker Compose {@code db} service must be running
 * ({@code bin/run-dev}). Tests run inside the {@code builder} container via
 * {@code docker exec ... sbt 'testOnly *IntegrationTest*'}.
 *
 * <p><b>CI behavior:</b> These tests are excluded from the default {@code sbt test} run via
 * a filter in {@code build.sbt}. If they are run without Postgres, they are gracefully skipped
 * via {@link org.junit.Assume#assumeTrue} (not failed).
 *
 * <p>Each test generates a unique sandbox ID so tests don't collide. {@link #cleanup()} removes
 * test rows after each test.
 */
public class SandboxRepositoryIntegrationTest {

  private static Application app;
  private SandboxRepository repo;
  private String testId;

  @BeforeClass
  public static void startApp() {
    // Check Postgres reachability BEFORE booting the Play app.
    // This prevents a 30-second connection timeout + NPE cascade in CI.
    assumeTrue(
        "Skipping: Postgres not reachable at db:5432 or localhost:5432",
        isPostgresReachable());

    app = new GuiceApplicationBuilder().build();
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
    // Double-check: if @BeforeClass skipped via assumeTrue, individual tests should also skip.
    assumeTrue("Skipping: no Postgres", app != null);

    Database db = app.injector().instanceOf(Database.class);
    repo = new SandboxRepository(db);
    // Ensure schema exists (idempotent — safe to call every time)
    repo.ensureSchemaInitialized();
    // Unique ID per test so tests are fully isolated
    testId = "sb-test-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @After
  public void cleanup() {
    // Guard against NPE when the app failed to start (e.g. no Postgres).
    if (app == null || testId == null) {
      return;
    }
    // Hard-delete the test row so it doesn't leak into the dev database
    Database db = app.injector().instanceOf(Database.class);
    db.withConnection(conn -> {
      try (var ps = conn.prepareStatement("DELETE FROM sandbox_instances WHERE id = ?")) {
        ps.setString(1, testId);
        ps.executeUpdate();
      }
      return null;
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /**
   * Checks whether Postgres is reachable on the expected host/port.
   * Tries "db" (Docker Compose service name) first, then localhost (for local dev outside Docker).
   */
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

  /** Builds a minimal valid SandboxInstance with all required (NOT NULL) columns populated. */
  private SandboxInstance makeTestInstance() {
    return SandboxInstance.builder()
        .id(testId)
        .cityName("Integration Test City")
        .subdomain("integ-test-" + testId.substring(8))
        .civiformVersion("latest")
        .status(SandboxStatus.PROVISIONING)
        .url("http://localhost:10099")
        .adminEmail("test@example.com")
        .pin("123456")
        .accessToken("tok-" + UUID.randomUUID())
        .containerId(null)
        .hostPort(10099)
        .databaseName("sandbox_" + testId.replace("-", "_"))
        .targetGroupArn(null)
        .listenerRuleArn(null)
        .listenerPriority(null)
        .googleAnalyticsId(null)
        .googleAnalyticsUrl(null)
        .createdAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        .expiresAt(Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS))
        .deletedAt(null)
        .build();
  }

  // ── save → findById round-trip ──────────────────────────────────────────────

  @Test
  public void save_thenFindById_allColumnsSurviveRoundTrip() {
    SandboxInstance original = makeTestInstance();
    repo.save(original);

    Optional<SandboxInstance> found = repo.findById(testId);

    assertThat(found).isPresent();
    SandboxInstance loaded = found.get();
    assertThat(loaded.getId()).isEqualTo(original.getId());
    assertThat(loaded.getCityName()).isEqualTo(original.getCityName());
    assertThat(loaded.getSubdomain()).isEqualTo(original.getSubdomain());
    assertThat(loaded.getCiviformVersion()).isEqualTo(original.getCiviformVersion());
    assertThat(loaded.getStatus()).isEqualTo(original.getStatus());
    assertThat(loaded.getUrl()).isEqualTo(original.getUrl());
    assertThat(loaded.getAdminEmail()).isEqualTo(original.getAdminEmail());
    assertThat(loaded.getPin()).isEqualTo(original.getPin());
    assertThat(loaded.getAccessToken()).isEqualTo(original.getAccessToken());
    assertThat(loaded.getContainerId()).isEqualTo(original.getContainerId());
    assertThat(loaded.getHostPort()).isEqualTo(original.getHostPort());
    assertThat(loaded.getDatabaseName()).isEqualTo(original.getDatabaseName());
    assertThat(loaded.getTargetGroupArn()).isEqualTo(original.getTargetGroupArn());
    assertThat(loaded.getListenerRuleArn()).isEqualTo(original.getListenerRuleArn());
    assertThat(loaded.getListenerPriority()).isEqualTo(original.getListenerPriority());
    assertThat(loaded.getGoogleAnalyticsId()).isEqualTo(original.getGoogleAnalyticsId());
    assertThat(loaded.getGoogleAnalyticsUrl()).isEqualTo(original.getGoogleAnalyticsUrl());
    assertThat(loaded.getCreatedAt()).isEqualTo(original.getCreatedAt());
    assertThat(loaded.getExpiresAt()).isEqualTo(original.getExpiresAt());
    assertThat(loaded.getDeletedAt()).isNull();
  }

  @Test
  public void save_thenFindById_nullableFieldsHandledCorrectly() {
    // Save with all nullable fields set to actual values
    SandboxInstance original = makeTestInstance().toBuilder()
        .containerId("container-abc123")
        .targetGroupArn("arn:aws:elasticloadbalancing:us-east-1:123:targetgroup/tg-test")
        .listenerRuleArn("arn:aws:elasticloadbalancing:us-east-1:123:listener-rule/lr-test")
        .listenerPriority(1042)
        .googleAnalyticsId("G-TEST123")
        .googleAnalyticsUrl("https://analytics.google.com/test")
        .build();
    repo.save(original);

    SandboxInstance loaded = repo.findById(testId).orElseThrow();
    assertThat(loaded.getContainerId()).isEqualTo("container-abc123");
    assertThat(loaded.getTargetGroupArn()).isEqualTo(original.getTargetGroupArn());
    assertThat(loaded.getListenerRuleArn()).isEqualTo(original.getListenerRuleArn());
    assertThat(loaded.getListenerPriority()).isEqualTo(1042);
    assertThat(loaded.getGoogleAnalyticsId()).isEqualTo("G-TEST123");
    assertThat(loaded.getGoogleAnalyticsUrl()).isEqualTo(original.getGoogleAnalyticsUrl());
  }

  // ── save → findAll ──────────────────────────────────────────────────────────

  @Test
  public void save_thenFindAll_containsSavedInstance() {
    repo.save(makeTestInstance());

    ImmutableList<SandboxInstance> all = repo.findAll();

    assertThat(all).anyMatch(s -> s.getId().equals(testId));
  }

  // ── save → softDelete → findById (PII scrubbing) ───────────────────────────

  @Test
  public void softDelete_scrubsPiiAndSetsStatus() {
    SandboxInstance original = makeTestInstance();
    repo.save(original);

    Instant deletedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    boolean updated = repo.softDelete(testId, deletedAt);

    assertThat(updated).isTrue();

    SandboxInstance loaded = repo.findById(testId).orElseThrow();
    // Status must be DELETED
    assertThat(loaded.getStatus()).isEqualTo(SandboxStatus.DELETED);
    // PII must be scrubbed
    assertThat(loaded.getPin()).isEmpty();
    assertThat(loaded.getAccessToken()).isEmpty();
    assertThat(loaded.getAdminEmail()).isEmpty();
    // Deleted timestamp must be set
    assertThat(loaded.getDeletedAt()).isEqualTo(deletedAt);
    // Non-PII fields must be preserved (audit trail)
    assertThat(loaded.getCityName()).isEqualTo(original.getCityName());
    assertThat(loaded.getDatabaseName()).isEqualTo(original.getDatabaseName());
    assertThat(loaded.getSubdomain()).isEqualTo(original.getSubdomain());
  }

  @Test
  public void softDelete_returnsFalseForNonexistentId() {
    boolean updated = repo.softDelete("sb-does-not-exist", Instant.now());
    assertThat(updated).isFalse();
  }

  // ── save → updateStatus → findById ─────────────────────────────────────────

  @Test
  public void updateStatus_persistsNewStatus() {
    repo.save(makeTestInstance());
    assertThat(repo.findById(testId).orElseThrow().getStatus())
        .isEqualTo(SandboxStatus.PROVISIONING);

    repo.updateStatus(testId, SandboxStatus.RUNNING);

    assertThat(repo.findById(testId).orElseThrow().getStatus())
        .isEqualTo(SandboxStatus.RUNNING);
  }

  @Test
  public void updateStatus_toFailed() {
    repo.save(makeTestInstance());

    repo.updateStatus(testId, SandboxStatus.FAILED);

    assertThat(repo.findById(testId).orElseThrow().getStatus())
        .isEqualTo(SandboxStatus.FAILED);
  }

  // ── save → updateContainerId → findById ────────────────────────────────────

  @Test
  public void updateContainerId_persistsContainerId() {
    repo.save(makeTestInstance());
    assertThat(repo.findById(testId).orElseThrow().getContainerId()).isNull();

    repo.updateContainerId(testId, "docker-container-xyz789");

    assertThat(repo.findById(testId).orElseThrow().getContainerId())
        .isEqualTo("docker-container-xyz789");
  }

  // ── save → updateExpiry → findById ─────────────────────────────────────────

  @Test
  public void updateExpiry_persistsNewExpiresAt() {
    repo.save(makeTestInstance());

    Instant newExpiry = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.MILLIS);
    boolean updated = repo.updateExpiry(testId, newExpiry);

    assertThat(updated).isTrue();
    assertThat(repo.findById(testId).orElseThrow().getExpiresAt()).isEqualTo(newExpiry);
  }

  @Test
  public void updateExpiry_returnsFalseForNonexistentId() {
    boolean updated = repo.updateExpiry("sb-does-not-exist", Instant.now());
    assertThat(updated).isFalse();
  }

  // ── save → updateTerraformOutputs → findById ──────────────────────────────

  @Test
  public void updateTerraformOutputs_persistsAwsResources() {
    repo.save(makeTestInstance());

    String url = "https://test.sandbox.civiform.dev";
    String tgArn = "arn:aws:elasticloadbalancing:us-east-1:123:targetgroup/tg-integ";
    String lrArn = "arn:aws:elasticloadbalancing:us-east-1:123:listener-rule/lr-integ";
    String tdArn = "arn:aws:ecs:us-east-1:123:task-definition/cf-integ:1";
    repo.updateTerraformOutputs(testId, url, tgArn, lrArn, tdArn);

    SandboxInstance loaded = repo.findById(testId).orElseThrow();
    assertThat(loaded.getUrl()).isEqualTo(url);
    assertThat(loaded.getTargetGroupArn()).isEqualTo(tgArn);
    assertThat(loaded.getListenerRuleArn()).isEqualTo(lrArn);
    // Task definition ARN is stored in container_id for Terraform sandboxes
    assertThat(loaded.getContainerId()).isEqualTo(tdArn);
  }

  // ── save → delete (tombstone) → findById ───────────────────────────────────

  @Test
  public void delete_setsStatusDeletedAndTimestamp() {
    repo.save(makeTestInstance());

    boolean deleted = repo.delete(testId);

    assertThat(deleted).isTrue();
    SandboxInstance loaded = repo.findById(testId).orElseThrow();
    assertThat(loaded.getStatus()).isEqualTo(SandboxStatus.DELETED);
    assertThat(loaded.getDeletedAt()).isNotNull();
  }

  @Test
  public void delete_returnsFalseForNonexistentId() {
    boolean deleted = repo.delete("sb-does-not-exist");
    assertThat(deleted).isFalse();
  }

  // ── failInterruptedOperations ──────────────────────────────────────────────

  @Test
  public void failInterruptedOperations_transitionsProvisioningToFailed() {
    repo.save(makeTestInstance()); // status = PROVISIONING

    int count = repo.failInterruptedOperations();

    assertThat(count).isGreaterThanOrEqualTo(1);
    assertThat(repo.findById(testId).orElseThrow().getStatus())
        .isEqualTo(SandboxStatus.FAILED);
  }

  // ── Sequence tests ─────────────────────────────────────────────────────────

  @Test
  public void nextPort_returnsIncreasingValues() {
    int port1 = repo.nextPort();
    int port2 = repo.nextPort();

    assertThat(port1).isGreaterThanOrEqualTo(10000);
    assertThat(port1).isLessThanOrEqualTo(11000);
    assertThat(port2).isEqualTo(port1 + 1);
  }

  @Test
  public void nextListenerPriority_returnsIncreasingValues() {
    int p1 = repo.nextListenerPriority();
    int p2 = repo.nextListenerPriority();

    assertThat(p1).isGreaterThanOrEqualTo(1000);
    assertThat(p2).isEqualTo(p1 + 2); // INCREMENT BY 2
  }

  // ── findById for missing row ───────────────────────────────────────────────

  @Test
  public void findById_returnsEmptyForNonexistentId() {
    Optional<SandboxInstance> result = repo.findById("sb-nonexistent-" + UUID.randomUUID());
    assertThat(result).isEmpty();
  }

  // ── ensureSchemaInitialized is idempotent ──────────────────────────────────

  @Test
  public void ensureSchemaInitialized_canRunTwice() {
    // Should not throw — all DDL in init_postgres.sql uses IF NOT EXISTS
    repo.ensureSchemaInitialized();
    repo.ensureSchemaInitialized();
  }

  // ── PersistedDurableJobRepository ──────────────────────────────────────────

  @Test
  public void persistedDurableJobRepository_roundTripScheduleClaimAndCleanup() throws Exception {
    Database db = app.injector().instanceOf(Database.class);
    Instant now = Instant.parse("2026-08-15T02:05:00Z");
    java.time.Clock clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
    PersistedDurableJobRepository jobRepo = new PersistedDurableJobRepository(db, clock);

    String uniqueJobName = "INTEG_TEST_JOB_" + UUID.randomUUID();
    Long createdId = null;
    Long staleId = null;
    try {
      models.PersistedDurableJobModel dueJob =
          new models.PersistedDurableJobModel(
              uniqueJobName, models.JobType.RECURRING, now.minusSeconds(60));

      models.PersistedDurableJobModel staleCompletedJob =
          models.PersistedDurableJobModel.builder()
              .jobName(uniqueJobName + "_OLD")
              .jobType(models.JobType.RECURRING)
              .executionTime(now.minus(Duration.ofDays(200)))
              .successTime(now.minus(Duration.ofDays(200)))
              .remainingAttempts(2)
              .build();

      try (java.sql.Connection conn = db.getConnection(true)) {
        jobRepo.save(conn, dueJob);
        jobRepo.save(conn, staleCompletedJob);
      }
      createdId = dueJob.getId();
      staleId = staleCompletedJob.getId();

      assertThat(createdId).isNotNull();
      assertThat(jobRepo.findScheduledRecurringJob(uniqueJobName)).isPresent();

      try (java.sql.Connection conn = db.getConnection(false)) {
        // Querying FOR UPDATE SKIP LOCKED should execute validly against Postgres 16.
        Optional<models.PersistedDurableJobModel> claimed = jobRepo.getRecurringJobForExecution(conn);
        assertThat(claimed).isPresent();
        conn.rollback();
      }

      int cleaned = jobRepo.deleteJobsOlderThanSixMonths();
      assertThat(cleaned).isGreaterThanOrEqualTo(1);
      final Long finalStaleId = staleId;
      assertThat(jobRepo.getJobs()).noneMatch(j -> finalStaleId.equals(j.getId()));
    } finally {
      final Long id1 = createdId;
      final Long id2 = staleId;
      db.withConnection(
          conn -> {
            if (id1 != null) {
              jobRepo.delete(conn, id1);
            }
            if (id2 != null) {
              jobRepo.delete(conn, id2);
            }
            return null;
          });
    }
  }
}
