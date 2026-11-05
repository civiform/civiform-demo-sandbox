package controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;
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
import java.util.concurrent.atomic.AtomicInteger;
import models.JobType;
import models.PersistedDurableJobModel;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import play.Application;
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

  @Override
  protected Application provideApplication() {
    sandboxService = mock(SandboxService.class);
    persistedDurableJobRepository = mock(PersistedDurableJobRepository.class);
    recurringDurableJobRunner = mock(RecurringDurableJobRunner.class);
    return new GuiceApplicationBuilder()
        .overrides(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(SandboxService.class).toInstance(sandboxService);
                bind(PersistedDurableJobRepository.class)
                    .toInstance(persistedDurableJobRepository);
                bind(RecurringDurableJobRunner.class).toInstance(recurringDurableJobRunner);
                bind(Clock.class).toInstance(FIXED_CLOCK);
              }
            })
        .build();
  }

  // ── Sandbox list footer link ──────────────────────────────────────────────

  @Test
  public void sandboxList_rendersFooterDevToolsLink() {
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
    assertThat(body).contains("Dev tools");
    assertThat(body).contains("id=\"durable-job-select\"");
    assertThat(body).contains("id=\"run-durable-job-button\"");
    assertThat(body).contains("action=\"/dev/runDurableJob\"");
    // Registered jobs from DurableJobModule must appear in the dropdown
    assertThat(body).contains("<option value=\"DESTROY_EXPIRED_SANDBOXES\">DESTROY_EXPIRED_SANDBOXES</option>");
    assertThat(body).contains("<option value=\"OLD_JOB_CLEANUP\">OLD_JOB_CLEANUP</option>");
    // Unregistered enum value TEST must NOT appear in the dropdown
    assertThat(body).doesNotContain("<option value=\"TEST\">");
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
        persisted ->
            new DurableJob() {
              @Override
              public PersistedDurableJobModel getPersistedDurableJob() {
                return persisted;
              }

              @Override
              public void run() {
                jobExecutions.incrementAndGet();
              }
            },
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
            .overrides(
                new AbstractModule() {
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
                })
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
  public void runDurableJob_whenJobRecordsError_redirectsWithErrorFlash() {
    when(persistedDurableJobRepository.save(any(PersistedDurableJobModel.class)))
        .thenAnswer(
            invocation -> {
              PersistedDurableJobModel saved = invocation.getArgument(0);
              saved.setId(202L);
              return saved;
            });
    PersistedDurableJobModel failedJob =
        PersistedDurableJobModel.builder()
            .id(202L)
            .jobName("DESTROY_EXPIRED_SANDBOXES")
            .jobType(JobType.RECURRING)
            .executionTime(FIXED_CLOCK.instant())
            .remainingAttempts(2)
            .errorMessage("Simulated teardown failure")
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
        .contains("Durable job DESTROY_EXPIRED_SANDBOXES failed.");
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
