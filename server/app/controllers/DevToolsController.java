package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import durablejobs.DurableJobName;
import durablejobs.DurableJobRegistry;
import durablejobs.JobNotFoundException;
import durablejobs.RecurringDurableJobRunner;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import models.JobType;
import models.PersistedDurableJobModel;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
import play.i18n.Messages;
import play.i18n.MessagesApi;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.PersistedDurableJobRepository;
import views.dev.DevToolsView;
import views.dev.DevToolsViewModel;

/**
 * Controller for the CiviForm-style developer debugging tools page ({@code /dev}).
 *
 * <p>Exposes a tool to select any durable job registered in {@link DurableJobRegistry} and execute
 * it immediately on demand via {@link RecurringDurableJobRunner}. Disabled by default in Play
 * {@code PROD} mode unless {@code DEV_TOOLS_ENABLED=true} ({@code dev_tools.enabled}) is set.
 */
@Singleton
public final class DevToolsController extends Controller {

  private static final ALogger log = Logger.of(DevToolsController.class);
  private static final int DEFAULT_SYNC_WAIT_SECONDS = 30;
  private static final long TEST_ZERO_SYNC_WAIT_MILLIS = 100L;
  static final int MAX_FLASH_ERROR_REASON_CHARS = 200;

  private final Environment environment;
  private final Config config;
  private final DurableJobRegistry durableJobRegistry;
  private final PersistedDurableJobRepository persistedDurableJobRepository;
  private final RecurringDurableJobRunner recurringDurableJobRunner;
  private final Clock clock;
  private final FormFactory formFactory;
  private final MessagesApi messagesApi;
  private final DevToolsView devToolsView;
  private final long syncWaitMillis;

  /**
   * Dedicated background pool for on-demand durable job runs triggered from {@code /dev}.
   *
   * <p>Kept separate from {@code ForkJoinPool.commonPool()} (which has parallelism 1 on 1–2 vCPU
   * containers) and from {@link durablejobs.DurableJobExecutionContext} ({@code fixed-pool-size =
   * 2}, onto which {@link durablejobs.AbstractDurableJobRunner} submits child job tasks while
   * blocking in {@code future.get()} — sharing that pool here would risk thread-pool deadlock under
   * concurrent requests).
   */
  private final ExecutorService devToolsExecutor;

  @Inject
  public DevToolsController(
      Environment environment,
      Config config,
      ApplicationLifecycle applicationLifecycle,
      @RecurringJobsProviderName DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository,
      RecurringDurableJobRunner recurringDurableJobRunner,
      Clock clock,
      FormFactory formFactory,
      MessagesApi messagesApi,
      DevToolsView devToolsView) {
    this.environment = checkNotNull(environment);
    this.config = checkNotNull(config);
    this.durableJobRegistry = checkNotNull(durableJobRegistry);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
    this.recurringDurableJobRunner = checkNotNull(recurringDurableJobRunner);
    this.clock = checkNotNull(clock);
    this.formFactory = checkNotNull(formFactory);
    this.messagesApi = checkNotNull(messagesApi);
    this.devToolsView = checkNotNull(devToolsView);

    int configuredSeconds =
        config.hasPath("dev_tools.sync_wait_seconds")
            ? config.getInt("dev_tools.sync_wait_seconds")
            : DEFAULT_SYNC_WAIT_SECONDS;
    this.syncWaitMillis =
        configuredSeconds <= 0
            ? TEST_ZERO_SYNC_WAIT_MILLIS
            : TimeUnit.SECONDS.toMillis(configuredSeconds);

    AtomicInteger threadCounter = new AtomicInteger(1);
    this.devToolsExecutor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread =
                  new Thread(runnable, "dev-tools-job-runner-" + threadCounter.getAndIncrement());
              thread.setDaemon(true);
              return thread;
            });
    checkNotNull(applicationLifecycle)
        .addStopHook(
            () -> {
              devToolsExecutor.shutdownNow();
              return CompletableFuture.completedFuture(null);
            });
  }

  /**
   * Returns true if developer tools ({@code /dev}) are enabled in the current environment.
   *
   * <p>Controlled by {@code dev_tools.enabled} ({@code DEV_TOOLS_ENABLED}) when explicitly set;
   * otherwise enabled automatically in Play {@code DEV} and {@code TEST} modes and disabled in
   * {@code PROD}.
   */
  public static boolean isDevToolsEnabled(Environment environment, Config config) {
    checkNotNull(environment);
    checkNotNull(config);
    if (config.hasPath("dev_tools.enabled")) {
      return config.getBoolean("dev_tools.enabled");
    }
    return !environment.isProd();
  }

  /** GET /dev — renders the developer tools page with all registered durable jobs. */
  @AddCSRFToken
  public CompletionStage<Result> index(Http.Request request) {
    if (!isDevToolsEnabled(environment, config)) {
      return CompletableFuture.completedFuture(notFound());
    }

    ImmutableList<String> durableJobNames =
        durableJobRegistry.getRegisteredJobs().stream()
            .map(job -> job.getJobName().getJobNameString())
            .collect(ImmutableList.toImmutableList());

    DevToolsViewModel model =
        DevToolsViewModel.builder()
            .durableJobNames(durableJobNames)
            .runDurableJobUrl(routes.DevToolsController.runDurableJob().url())
            .sandboxesUrl(routes.SandboxController.index().url())
            .build();

    return CompletableFuture.completedFuture(
        ok(devToolsView.render(request, model)).as("text/html"));
  }

  /**
   * POST /dev/runDurableJob — persists a one-time execution record for the selected registered
   * durable job and runs it immediately via {@link RecurringDurableJobRunner#runJobs()}.
   */
  @RequireCSRFCheck
  public CompletionStage<Result> runDurableJob(Http.Request request) {
    if (!isDevToolsEnabled(environment, config)) {
      return CompletableFuture.completedFuture(notFound());
    }

    Messages messages = messagesApi.preferred(request);
    String jobName = extractJobName(request);
    if (jobName.isEmpty()) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, messages.at("devtools.jobs.error.emptySelection")));
    }

    Optional<DurableJobName> optionalJobName = DurableJobName.optionalValueOf(jobName);
    if (optionalJobName.isEmpty()) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, messages.at("devtools.jobs.error.unknown", jobName)));
    }

    DurableJobRegistry.RegisteredJob registeredJob;
    try {
      registeredJob = durableJobRegistry.get(optionalJobName.get());
    } catch (JobNotFoundException e) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, messages.at("devtools.jobs.error.unregistered", jobName)));
    }

    String resolvedJobName = registeredJob.getJobName().getJobNameString();
    CompletableFuture<Result> executionFuture =
        CompletableFuture.supplyAsync(
            () -> executeDurableJobNow(request, messages, resolvedJobName), devToolsExecutor);

    return executionFuture
        .thenApply(result -> result)
        .completeOnTimeout(
            backgroundRunningResult(request, messages, resolvedJobName),
            syncWaitMillis,
            TimeUnit.MILLISECONDS);
  }

  private Result executeDurableJobNow(
      Http.Request request, Messages messages, String resolvedJobName) {
    try {
      // Set JobType.RECURRING and execution_time <= now so RecurringDurableJobRunner claims and
      // executes this on-demand row immediately, and OldJobCleanupJob purges it after 6 months
      // alongside regular recurring job history.
      PersistedDurableJobModel persistedJob =
          persistedDurableJobRepository.save(
              new PersistedDurableJobModel(resolvedJobName, JobType.RECURRING, clock.instant()));
      recurringDurableJobRunner.runJobs();

      Optional<PersistedDurableJobModel> executedJob =
          persistedJob != null && persistedJob.getId() != null
              ? persistedDurableJobRepository.findById(persistedJob.getId())
              : Optional.empty();

      if (executedJob.isPresent() && executedJob.get().getSuccessTime().isPresent()) {
        String successMsg = messages.at("devtools.jobs.flash.succeeded", resolvedJobName);
        if (isJsonRequest(request)) {
          return ok(Json.newObject().put("jobName", resolvedJobName).put("status", "COMPLETED"));
        }
        return redirect(controllers.routes.DevToolsController.index())
            .flashing("success", successMsg);
      }

      if (executedJob.isPresent() && executedJob.get().getErrorMessage().isPresent()) {
        PersistedDurableJobModel failedJob = executedJob.get();
        // Manual /dev runs are one-shot ("Choose job to run once"): clear remainingAttempts after
        // runJobs() returns so advanceExecutionTimeForRetry() does not leave a duplicate retry row
        // scheduled for the next cron window alongside RecurringJobScheduler's row, while also
        // avoiding spurious final-failure Slack alerts inside runJobs().
        if (failedJob.getRemainingAttempts() > 0) {
          failedJob.setRemainingAttempts(0);
          persistedDurableJobRepository.save(failedJob);
        }
        Optional<String> reason = summarizeErrorMessage(failedJob.getErrorMessage().get());
        String errorMsg =
            reason.isPresent()
                ? messages.at("devtools.jobs.error.failedWithReason", resolvedJobName, reason.get())
                : messages.at("devtools.jobs.error.failed", resolvedJobName);
        if (isJsonRequest(request)) {
          return internalServerError(
              Json.newObject()
                  .put("jobName", resolvedJobName)
                  .put("status", "FAILED")
                  .put("error", errorMsg));
        }
        return redirect(controllers.routes.DevToolsController.index())
            .flashing("error", errorMsg);
      }

      String incompleteMsg = messages.at("devtools.jobs.error.incomplete", resolvedJobName);
      if (isJsonRequest(request)) {
        return internalServerError(
            Json.newObject()
                .put("jobName", resolvedJobName)
                .put("status", "INCOMPLETE")
                .put("error", incompleteMsg));
      }
      return redirect(controllers.routes.DevToolsController.index())
          .flashing("error", incompleteMsg);
    } catch (RuntimeException e) {
      log.error("Failed running durable job {} from dev tools", resolvedJobName, e);
      String errorMsg = messages.at("devtools.jobs.error.exception", resolvedJobName);
      if (isJsonRequest(request)) {
        return internalServerError(Json.newObject().put("error", errorMsg));
      }
      return redirect(controllers.routes.DevToolsController.index()).flashing("error", errorMsg);
    }
  }

  private Result backgroundRunningResult(
      Http.Request request, Messages messages, String resolvedJobName) {
    String runningMsg = messages.at("devtools.jobs.flash.runningInBackground", resolvedJobName);
    if (isJsonRequest(request)) {
      return status(
          Http.Status.ACCEPTED,
          Json.newObject()
              .put("jobName", resolvedJobName)
              .put("status", "RUNNING")
              .put("message", runningMsg));
    }
    return redirect(controllers.routes.DevToolsController.index()).flashing("success", runningMsg);
  }

  @VisibleForTesting
  static Optional<String> summarizeErrorMessage(String rawError) {
    if (rawError == null || rawError.isBlank()) {
      return Optional.empty();
    }
    int traceIdx = rawError.indexOf(", trace=");
    String withoutTrace = traceIdx >= 0 ? rawError.substring(0, traceIdx) : rawError;
    String firstLine = withoutTrace.lines().findFirst().orElse("").trim();
    if (firstLine.isEmpty()) {
      return Optional.empty();
    }
    if (firstLine.length() > MAX_FLASH_ERROR_REASON_CHARS) {
      return Optional.of(firstLine.substring(0, MAX_FLASH_ERROR_REASON_CHARS) + "...");
    }
    return Optional.of(firstLine);
  }

  private String extractJobName(Http.Request request) {
    if (request.hasBody() && request.body().asJson() != null) {
      JsonNode json = request.body().asJson();
      if (json.has("durableJobSelect")) {
        return json.get("durableJobSelect").asText("").trim();
      }
      if (json.has("jobName")) {
        return json.get("jobName").asText("").trim();
      }
      return "";
    }
    DynamicForm form = formFactory.form().bindFromRequest(request);
    String selected = form.get("durableJobSelect");
    if (selected != null && !selected.isBlank()) {
      return selected.trim();
    }
    String fallback = form.get("jobName");
    return fallback != null ? fallback.trim() : "";
  }

  private Result validationErrorResult(Http.Request request, String message) {
    if (isJsonRequest(request)) {
      return badRequest(Json.newObject().put("error", message));
    }
    return redirect(controllers.routes.DevToolsController.index()).flashing("error", message);
  }

  private static boolean isJsonRequest(Http.Request request) {
    return request.accepts("application/json") && !request.accepts("text/html");
  }
}
