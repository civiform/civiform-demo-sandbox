package controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.ACCEPTED;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;
import static play.mvc.Http.Status.NOT_FOUND;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.SEE_OTHER;
import static play.test.Helpers.contentAsString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import durablejobs.DurableJob;
import durablejobs.DurableJobName;
import durablejobs.DurableJobRegistry;
import durablejobs.RecurringDurableJobRunner;
import durablejobs.RecurringJobExecutionTimeResolvers;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import play.Application;
import play.Environment;
import play.Mode;
import play.db.Database;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;
import services.PersistedDurableJobRepository;
import services.SandboxService;
import services.SlackAlertClient;

/**
 * Unit tests for {@link DevToolsController} and the footer link on {@code /sandboxes}.
 *
 * <p>Exercises the full Play router + Guice wiring + Thymeleaf rendering without requiring a live
 * database or Docker daemon.
 */
public class DevToolsControllerTest extends WithApplication {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T14:30:00Z"), ZoneOffset.UTC);

  private SandboxService sandboxService;
  private PersistedDurableJobRepository persistedDurableJobRepository;
  private RecurringDurableJobRunner recurringDurableJobRunner;

  /** Named static module so Zinc's JDK 21 {@code -parameters} API extractor discovers the suite. */
  private static final class TestOverridesModule extends AbstractModule {
    private final SandboxService sandboxService;
    private final PersistedDurableJobRepository persistedDurableJobRepository;
    private final RecurringDurableJobRunner recurringDurableJobRunner;

    TestOverridesModule(
        SandboxService sandboxService,
        PersistedDurableJobRepository persistedDurableJobRepository,
        RecurringDurableJobRunner recurringDurableJobRunner) {
      this.sandboxService = sandboxService;
      this.persistedDurableJobRepository = persistedDurableJobRepository;
      this.recurringDurableJobRunner = recurringDurableJobRunner;
    }

    @Override
    protected void configure() {
      bind(SandboxService.class).toInstance(sandboxService);
      bind(PersistedDurableJobRepository.class).toInstance(persistedDurableJobRepository);
      bind(RecurringDurableJobRunner.class).toInstance(recurringDurableJobRunner);
      bind(Clock.class).toInstance(FIXED_CLOCK);
    }
  }

  private static final class RealRunnerOverridesModule extends AbstractModule {
    private final SandboxService sandboxService;
    private final Database mockDb;
    private final PersistedDurableJobRepository repo;
    private final DurableJobRegistry customRegistry;

    RealRunnerOverridesModule(
        SandboxService sandboxService,
        Database mockDb,
        PersistedDurableJobRepository repo,
        DurableJobRegistry customRegistry) {
      this.sandboxService = sandboxService;
      this.mockDb = mockDb;
      this.repo = repo;
      this.customRegistry = customRegistry;
    }

    @Override
    protected void configure() {
      bind(SandboxService.class).toInstance(sandboxService);
      bind(Database.class).toInstance(mockDb);
      bind(PersistedDurableJobRepository.class).toInstance(repo);
      bind(SlackAlertClient.class).toInstance(mock(SlackAlertClient.class));
      bind(Clock.class).toInstance(FIXED_CLOCK);
      bind(Key.get(DurableJobRegistry.class, RecurringJobsProviderName.class))
          .toInstance(customRegistry);
    }
  }

  private static final class CountingDurableJob extends DurableJob {
    private final PersistedDurableJobModel persisted;
    private final AtomicInteger counter;

    CountingDurableJob(PersistedDurableJobModel persisted, AtomicInteger counter) {
      this.persisted = persisted;
      this.counter = counter;
    }

    @Override
    public PersistedDurableJobModel getPersistedDurableJob() {
      return persisted;
    }

    @Override
    public void run() {
      counter.incrementAndGet();
    }
  }

  @Override
  protected Application provideApplication() {
    sandboxService = mock(SandboxService.class);
    persistedDurableJobRepository = mock(PersistedDurableJobRepository.class);
    recurringDurableJobRunner = mock(RecurringDurableJobRunner.class);
    return new GuiceApplicationBuilder()
        .overrides(
            new TestOverridesModule(
                sandboxService, persistedDurableJobRepository, recurringDurableJobRunner))
        .build();
  }

  // ── Sandbox list footer link & prod gating ────────────────────────────────

  @Test
  public void sandboxList_rendersFooterDevToolsLinkWhenEnabled() {
    when(sandboxService.listSandboxes())
        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("GET")
            .uri("/sandboxes")
            .session(AuthController.SESSION_KEY, "true");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    String body = contentAsString(result);
    assertThat(body).contains("id=\"dev-tools-link\"");
    assertThat(body).contains("href=\"/dev\"");
    assertThat(body).contains("Dev tools");
  }

  @Test
  public void prodMode_disablesDevToolsAndHidesFooterLinkByDefault() {
    when(sandboxService.listSandboxes())
        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

    Application prodApp =
        new GuiceApplicationBuilder()
            .in(new Environment(app.path(), app.classloader(), Mode.PROD))
            .configure("durable_jobs.enabled", false)
            .overrides(
                new TestOverridesModule(
                    sandboxService, persistedDurableJobRepository, recurringDurableJobRunner))
            .build();

    try {
      Result sandboxesResult =
          Helpers.route(
              prodApp,
              Helpers.fakeRequest()
                  .method("GET")
                  .uri("/sandboxes")
                  .session(AuthController.SESSION_KEY, "true"));
      assertThat(sandboxesResult.status()).isEqualTo(OK);
      assertThat(contentAsString(sandboxesResult)).doesNotContain("id=\"dev-tools-link\"");

      Result indexResult =
          Helpers.route(
              prodApp,
              Helpers.fakeRequest()
                  .method("GET")
                  .uri("/dev")
                  .session(AuthController.SESSION_KEY, "true"));
      assertThat(indexResult.status()).isEqualTo(NOT_FOUND);

      Result runResult =
          Helpers.route(
              prodApp,
              Helpers.fakeRequest()
                  .method("POST")
                  .uri("/dev/runDurableJob")
                  .session(AuthController.SESSION_KEY, "true")
                  .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP")));
      assertThat(runResult.status()).isEqualTo(NOT_FOUND);
      verify(persistedDurableJobRepository, never()).save(any(PersistedDurableJobModel.class));
      verify(recurringDurableJobRunner, never()).runJobs();
    } finally {
      Helpers.stop(prodApp);
    }
  }

  @Test
  public void prodMode_whenExplicitlyEnabledViaConfig_allowsAccessAndShowsFooterLink() {
    when(sandboxService.listSandboxes())
        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

    Application prodEnabledApp =
        new GuiceApplicationBuilder()
            .in(new Environment(app.path(), app.classloader(), Mode.PROD))
            .configure("durable_jobs.enabled", false)
            .configure("dev_tools.enabled", true)
            .overrides(
                new TestOverridesModule(
                    sandboxService, persistedDurableJobRepository, recurringDurableJobRunner))
            .build();

    try {
      Result sandboxesResult =
          Helpers.route(
              prodEnabledApp,
              Helpers.fakeRequest()
                  .method("GET")
                  .uri("/sandboxes")
                  .session(AuthController.SESSION_KEY, "true"));
      assertThat(sandboxesResult.status()).isEqualTo(OK);
      assertThat(contentAsString(sandboxesResult)).contains("id=\"dev-tools-link\"");

      Result indexResult =
          Helpers.route(
              prodEnabledApp,
              Helpers.fakeRequest()
                  .method("GET")
                  .uri("/dev")
                  .session(AuthController.SESSION_KEY, "true"));
      assertThat(indexResult.status()).isEqualTo(OK);
    } finally {
      Helpers.stop(prodEnabledApp);
    }
  }

  // ── GET /dev ──────────────────────────────────────────────────────────────

  @Test
  public void index_withoutAuth_redirectsToLogin() {
    Http.RequestBuilder request = Helpers.fakeRequest().method("GET").uri("/dev");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).contains("/login");
  }

  @Test
  public void index_rendersOnlyRegisteredDurableJobsAndRunButton() {
    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("GET")
            .uri("/dev")
            .session(AuthController.SESSION_KEY, "true");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    String body = contentAsString(result);
    assertThat(body).contains("<title>Dev tools - CiviForm Sandbox Builder</title>");
    assertThat(body).contains("href=\"/sandboxes\"");
    assertThat(body).contains("id=\"durable-job-select\"");
    assertThat(body).contains("id=\"run-durable-job-button\"");
    assertThat(body).contains("action=\"/dev/runDurableJob\"");
    assertThat(body)
        .contains("value=\"DESTROY_EXPIRED_SANDBOXES\"")
        .contains(">DESTROY_EXPIRED_SANDBOXES</option>");
    assertThat(body).contains("value=\"OLD_JOB_CLEANUP\"").contains(">OLD_JOB_CLEANUP</option>");
    assertThat(body).doesNotContain("value=\"TEST\"");
  }

  @Test
  public void index_displaysFlashSuccessAndErrorBanners() {
    Result successResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("GET")
                .uri("/dev")
                .session(AuthController.SESSION_KEY, "true")
                .flash("success", "Ran durable job: OLD_JOB_CLEANUP"));
    assertThat(contentAsString(successResult)).contains("Ran durable job: OLD_JOB_CLEANUP");

    Result errorResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("GET")
                .uri("/dev")
                .session(AuthController.SESSION_KEY, "true")
                .flash("error", "Unknown durable job: INVALID"));
    assertThat(contentAsString(errorResult)).contains("Unknown durable job: INVALID");
  }

  // ── POST /dev/runDurableJob ───────────────────────────────────────────────

  @Test
  public void runDurableJob_withoutAuth_redirectsToLogin() {
    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("POST")
            .uri("/dev/runDurableJob")
            .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).contains("/login");
    verify(persistedDurableJobRepository, never()).save(any(PersistedDurableJobModel.class));
    verify(recurringDurableJobRunner, never()).runJobs();
  }

  @Test
  public void runDurableJob_validRegisteredJob_persistsJobAndRunsImmediately() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            invocation -> {
              PersistedDurableJobModel saved = invocation.getArgument(0);
              saved.setId(101L);
              return saved;
            });
    PersistedDurableJobModel completedJob =
        PersistedDurableJobModel.builder()
            .id(101L)
            .jobName("OLD_JOB_CLEANUP")
            .jobType(JobType.RECURRING)
            .executionTime(FIXED_CLOCK.instant())
            .successTime(FIXED_CLOCK.instant())
            .remainingAttempts(2)
            .build();
    when(persistedDurableJobRepository.findById(101L)).thenReturn(Optional.of(completedJob));

    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("POST")
            .uri("/dev/runDurableJob")
            .session(AuthController.SESSION_KEY, "true")
            .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).contains(routes.DevToolsController.index().url());
    assertThat(result.flash().get("success")).contains("Ran durable job: OLD_JOB_CLEANUP");

    ArgumentCaptor<PersistedDurableJobModel> jobCaptor =
        ArgumentCaptor.forClass(PersistedDurableJobModel.class);
    InOrder inOrder = Mockito.inOrder(persistedDurableJobRepository, recurringDurableJobRunner);
    inOrder.verify(persistedDurableJobRepository).save(jobCaptor.capture());
    inOrder.verify(recurringDurableJobRunner).runJobs();

    PersistedDurableJobModel savedJob = jobCaptor.getValue();
    assertThat(savedJob.getJobName()).isEqualTo("OLD_JOB_CLEANUP");
    assertThat(savedJob.getJobType()).isEqualTo(JobType.RECURRING);
    assertThat(savedJob.getExecutionTime()).isEqualTo(FIXED_CLOCK.instant());
  }

  @Test
  public void runDurableJob_executesRegisteredJobBodyImmediatelyViaRealRunner() throws Exception {
    AtomicInteger jobExecutions = new AtomicInteger(0);
    DurableJobRegistry customRegistry = new DurableJobRegistry();
    customRegistry.register(
        DurableJobName.OLD_JOB_CLEANUP,
        JobType.RECURRING,
        persisted -> new CountingDurableJob(persisted, jobExecutions),
        new RecurringJobExecutionTimeResolvers.Sunday2Am());

    Database mockDb = mock(Database.class);
    Connection mockConn = mock(Connection.class);
    when(mockDb.getConnection(false)).thenReturn(mockConn);

    PersistedDurableJobRepository repo = mock(PersistedDurableJobRepository.class);
    AtomicInteger claimed = new AtomicInteger(0);
    PersistedDurableJobModel[] storedHolder = new PersistedDurableJobModel[1];

    when(repo.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            inv -> {
              PersistedDurableJobModel m = inv.getArgument(0);
              m.setId(77L);
              storedHolder[0] = m;
              return m;
            });
    when(repo.save(eq(mockConn), any(PersistedDurableJobModel.class)))
        .thenAnswer(inv -> inv.getArgument(1));
    when(repo.getRecurringJobForExecution(mockConn))
        .thenAnswer(
            inv ->
                claimed.getAndIncrement() == 0 && storedHolder[0] != null
                    ? Optional.of(storedHolder[0])
                    : Optional.empty());
    when(repo.findById(77L)).thenAnswer(inv -> Optional.ofNullable(storedHolder[0]));

    Application realRunnerApp =
        new GuiceApplicationBuilder()
            .overrides(new RealRunnerOverridesModule(sandboxService, mockDb, repo, customRegistry))
            .build();

    try {
      Result result =
          Helpers.route(
              realRunnerApp,
              Helpers.fakeRequest()
                  .method("POST")
                  .uri("/dev/runDurableJob")
                  .session(AuthController.SESSION_KEY, "true")
                  .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP")));

      assertThat(result.status()).isEqualTo(SEE_OTHER);
      assertThat(result.flash().get("success")).contains("Ran durable job: OLD_JOB_CLEANUP");
      assertThat(jobExecutions.get()).isEqualTo(1);
      assertThat(storedHolder[0].getSuccessTime()).contains(FIXED_CLOCK.instant());
    } finally {
      Helpers.stop(realRunnerApp);
    }
  }

  @Test
  public void runDurableJob_whenJobRecordsError_clearsRetriesAndFlashesTruncatedReason() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            invocation -> {
              PersistedDurableJobModel saved = invocation.getArgument(0);
              if (saved.getId() == null) {
                saved.setId(202L);
              }
              return saved;
            });
    PersistedDurableJobModel failedJob =
        PersistedDurableJobModel.builder()
            .id(202L)
            .jobName("DESTROY_EXPIRED_SANDBOXES")
            .jobType(JobType.RECURRING)
            .executionTime(Instant.parse("2026-08-16T02:00:00Z"))
            .remainingAttempts(2)
            .errorMessage(
                "Simulated teardown failure, trace=java.util.concurrent.ExecutionException: boom\n"
                    + "\tat durablejobs.AbstractDurableJobRunner.runJob(AbstractDurableJobRunner.java:224)")
            .build();
    when(persistedDurableJobRepository.findById(202L)).thenReturn(Optional.of(failedJob));

    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("POST")
            .uri("/dev/runDurableJob")
            .session(AuthController.SESSION_KEY, "true")
            .bodyForm(ImmutableMap.of("durableJobSelect", "DESTROY_EXPIRED_SANDBOXES"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).contains(routes.DevToolsController.index().url());
    assertThat(result.flash().get("error"))
        .contains("Durable job DESTROY_EXPIRED_SANDBOXES failed: Simulated teardown failure");
    assertThat(result.flash().get("error").get()).doesNotContain("ExecutionException");
    assertThat(failedJob.getRemainingAttempts()).isEqualTo(0);
    verify(persistedDurableJobRepository).save(failedJob);
  }

  @Test
  public void runDurableJob_whenJobDoesNotCompleteDuringRunnerPass_reportsIncompleteNotSuccess() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            invocation -> {
              PersistedDurableJobModel saved = invocation.getArgument(0);
              saved.setId(404L);
              return saved;
            });
    PersistedDurableJobModel unexecutedJob =
        PersistedDurableJobModel.builder()
            .id(404L)
            .jobName("OLD_JOB_CLEANUP")
            .jobType(JobType.RECURRING)
            .executionTime(FIXED_CLOCK.instant())
            .remainingAttempts(3)
            .build();
    when(persistedDurableJobRepository.findById(404L)).thenReturn(Optional.of(unexecutedJob));

    Result htmlResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("POST")
                .uri("/dev/runDurableJob")
                .session(AuthController.SESSION_KEY, "true")
                .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP")));
    assertThat(htmlResult.status()).isEqualTo(SEE_OTHER);
    assertThat(htmlResult.flash().get("success")).isEmpty();
    assertThat(htmlResult.flash().get("error"))
        .contains("Durable job OLD_JOB_CLEANUP did not complete during this runner pass.");

    Result jsonResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("POST")
                .uri("/dev/runDurableJob")
                .header("Accept", "application/json")
                .session(AuthController.SESSION_KEY, "true")
                .bodyJson(Json.newObject().put("durableJobSelect", "OLD_JOB_CLEANUP")));
    assertThat(jsonResult.status()).isEqualTo(INTERNAL_SERVER_ERROR);
    assertThat(contentAsString(jsonResult)).contains("\"status\":\"INCOMPLETE\"");
  }

  @Test
  public void runDurableJob_whenJobExceedsSyncWait_returnsAcceptedWhileContinuingInBackground()
      throws Exception {
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch releaseLatch = new CountDownLatch(1);
    CountDownLatch finishedLatch = new CountDownLatch(1);

    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            inv -> {
              PersistedDurableJobModel saved = inv.getArgument(0);
              saved.setId(505L);
              return saved;
            });
    doAnswer(
            inv -> {
              startedLatch.countDown();
              releaseLatch.await(5, TimeUnit.SECONDS);
              finishedLatch.countDown();
              return null;
            })
        .when(recurringDurableJobRunner)
        .runJobs();

    Application fastTimeoutApp =
        new GuiceApplicationBuilder()
            .configure("dev_tools.sync_wait_seconds", 0)
            .overrides(
                new TestOverridesModule(
                    sandboxService, persistedDurableJobRepository, recurringDurableJobRunner))
            .build();

    try {
      Result jsonResult =
          Helpers.route(
              fastTimeoutApp,
              Helpers.fakeRequest()
                  .method("POST")
                  .uri("/dev/runDurableJob")
                  .header("Accept", "application/json")
                  .session(AuthController.SESSION_KEY, "true")
                  .bodyJson(Json.newObject().put("durableJobSelect", "DESTROY_EXPIRED_SANDBOXES")));

      assertThat(startedLatch.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(jsonResult.status()).isEqualTo(ACCEPTED);
      assertThat(contentAsString(jsonResult)).contains("\"status\":\"RUNNING\"");

      releaseLatch.countDown();
      assertThat(finishedLatch.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseLatch.countDown();
      Helpers.stop(fastTimeoutApp);
    }
  }

  @Test
  public void runDurableJob_whenRunnerThrowsException_redirectsWithErrorFlash() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new RuntimeException("DB unavailable")).when(recurringDurableJobRunner).runJobs();

    Http.RequestBuilder request =
        Helpers.fakeRequest()
            .method("POST")
            .uri("/dev/runDurableJob")
            .session(AuthController.SESSION_KEY, "true")
            .bodyForm(ImmutableMap.of("durableJobSelect", "OLD_JOB_CLEANUP"));

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.flash().get("error")).contains("Failed to run durable job OLD_JOB_CLEANUP.");
  }

  @Test
  public void runDurableJob_blankUnknownOrUnregisteredJob_redirectsWithErrorFlashWithoutRunning() {
    for (String invalidJob : ImmutableList.of("", "NOT_A_REAL_JOB", "TEST")) {
      Http.RequestBuilder request =
          Helpers.fakeRequest()
              .method("POST")
              .uri("/dev/runDurableJob")
              .session(AuthController.SESSION_KEY, "true")
              .bodyForm(ImmutableMap.of("durableJobSelect", invalidJob));

      Result result = Helpers.route(app, request);

      assertThat(result.status()).isEqualTo(SEE_OTHER);
      assertThat(result.redirectLocation()).contains(routes.DevToolsController.index().url());
      assertThat(result.flash().get("error")).isPresent();
    }

    verify(persistedDurableJobRepository, never()).save(any(PersistedDurableJobModel.class));
    verify(recurringDurableJobRunner, never()).runJobs();
  }

  @Test
  public void runDurableJob_jsonRequests_returnAppropriateStatusCodes() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            invocation -> {
              PersistedDurableJobModel saved = invocation.getArgument(0);
              saved.setId(303L);
              return saved;
            });
    when(persistedDurableJobRepository.findById(303L))
        .thenReturn(
            Optional.of(
                PersistedDurableJobModel.builder()
                    .id(303L)
                    .jobName("OLD_JOB_CLEANUP")
                    .jobType(JobType.RECURRING)
                    .executionTime(FIXED_CLOCK.instant())
                    .successTime(FIXED_CLOCK.instant())
                    .remainingAttempts(2)
                    .build()));

    Result okResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("POST")
                .uri("/dev/runDurableJob")
                .header("Accept", "application/json")
                .session(AuthController.SESSION_KEY, "true")
                .bodyJson(Json.newObject().put("durableJobSelect", "OLD_JOB_CLEANUP")));
    assertThat(okResult.status()).isEqualTo(OK);
    assertThat(contentAsString(okResult)).contains("\"status\":\"COMPLETED\"");

    Result badRequestResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("POST")
                .uri("/dev/runDurableJob")
                .header("Accept", "application/json")
                .session(AuthController.SESSION_KEY, "true")
                .bodyJson(Json.newObject().put("durableJobSelect", "TEST")));
    assertThat(badRequestResult.status()).isEqualTo(BAD_REQUEST);

    doThrow(new RuntimeException("boom")).when(recurringDurableJobRunner).runJobs();
    Result errorResult =
        Helpers.route(
            app,
            Helpers.fakeRequest()
                .method("POST")
                .uri("/dev/runDurableJob")
                .header("Accept", "application/json")
                .session(AuthController.SESSION_KEY, "true")
                .bodyJson(Json.newObject().put("jobName", "OLD_JOB_CLEANUP")));
    assertThat(errorResult.status()).isEqualTo(INTERNAL_SERVER_ERROR);
  }

  @Test
  public void summarizeErrorMessage_stripsStackTraceAndTruncatesLongMessages() {
    assertThat(DevToolsController.summarizeErrorMessage(null)).isEmpty();
    assertThat(DevToolsController.summarizeErrorMessage("   ")).isEmpty();
    assertThat(
            DevToolsController.summarizeErrorMessage(
                "First line error, trace=java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.java:1)"))
        .contains("First line error");

    String overLimit = "x".repeat(DevToolsController.MAX_FLASH_ERROR_REASON_CHARS + 25);
    Optional<String> summarized = DevToolsController.summarizeErrorMessage(overLimit);
    assertThat(summarized).isPresent();
    assertThat(summarized.get())
        .hasSize(DevToolsController.MAX_FLASH_ERROR_REASON_CHARS + 3)
        .endsWith("...");
  }

  @Test
  public void csrfAnnotations_presentOnDevToolsEndpoints() throws NoSuchMethodException {
    assertThat(
            DevToolsController.class
                .getMethod("index", Http.Request.class)
                .isAnnotationPresent(AddCSRFToken.class))
        .isTrue();
    assertThat(
            DevToolsController.class
                .getMethod("runDurableJob", Http.Request.class)
                .isAnnotationPresent(RequireCSRFCheck.class))
        .isTrue();
  }
}
