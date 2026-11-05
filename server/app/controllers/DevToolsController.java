package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import durablejobs.BindingAnnotations.RecurringJobsProviderName;
import durablejobs.DurableJobName;
import durablejobs.DurableJobRegistry;
import durablejobs.JobNotFoundException;
import durablejobs.RecurringDurableJobRunner;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import models.JobType;
import models.PersistedDurableJobModel;
import play.Logger;
import play.Logger.ALogger;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
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
 * it immediately on demand via {@link RecurringDurableJobRunner}.
 */
public final class DevToolsController extends Controller {

  private static final ALogger log = Logger.of(DevToolsController.class);

  private final DurableJobRegistry durableJobRegistry;
  private final PersistedDurableJobRepository persistedDurableJobRepository;
  private final RecurringDurableJobRunner recurringDurableJobRunner;
  private final Clock clock;
  private final FormFactory formFactory;
  private final DevToolsView devToolsView;

  @Inject
  public DevToolsController(
      @RecurringJobsProviderName DurableJobRegistry durableJobRegistry,
      PersistedDurableJobRepository persistedDurableJobRepository,
      RecurringDurableJobRunner recurringDurableJobRunner,
      Clock clock,
      FormFactory formFactory,
      DevToolsView devToolsView) {
    this.durableJobRegistry = checkNotNull(durableJobRegistry);
    this.persistedDurableJobRepository = checkNotNull(persistedDurableJobRepository);
    this.recurringDurableJobRunner = checkNotNull(recurringDurableJobRunner);
    this.clock = checkNotNull(clock);
    this.formFactory = checkNotNull(formFactory);
    this.devToolsView = checkNotNull(devToolsView);
  }

  /** GET /dev — renders the developer tools page with all registered durable jobs. */
  @AddCSRFToken
  public CompletionStage<Result> index(Http.Request request) {
    ImmutableList<String> durableJobNames =
        durableJobRegistry.getRegisteredJobs().stream()
            .map(job -> job.getJobName().getJobNameString())
            .collect(ImmutableList.toImmutableList());

    DevToolsViewModel model =
        DevToolsViewModel.builder()
            .durableJobNames(durableJobNames)
            .runDurableJobUrl(routes.DevToolsController.runDurableJob().url())
            .build();

    return CompletableFuture.completedFuture(ok(devToolsView.render(request, model)).as("text/html"));
  }

  /**
   * POST /dev/runDurableJob — persists a one-time execution record for the selected registered
   * durable job and runs it immediately via {@link RecurringDurableJobRunner#runJobs()}.
   */
  @RequireCSRFCheck
  public CompletionStage<Result> runDurableJob(Http.Request request) {
    String jobName = extractJobName(request);
    if (jobName.isEmpty()) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, "Please select a durable job to run."));
    }

    Optional<DurableJobName> optionalJobName = DurableJobName.optionalValueOf(jobName);
    if (optionalJobName.isEmpty()) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, "Unknown durable job: " + jobName));
    }

    DurableJobRegistry.RegisteredJob registeredJob;
    try {
      registeredJob = durableJobRegistry.get(optionalJobName.get());
    } catch (JobNotFoundException e) {
      return CompletableFuture.completedFuture(
          validationErrorResult(request, "Durable job is not registered: " + jobName));
    }

    String resolvedJobName = registeredJob.getJobName().getJobNameString();
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            // Set JobType.RECURRING and execution_time <= now so RecurringDurableJobRunner claims
            // and runs it immediately, matching CiviForm's on-demand durable job pattern.
            PersistedDurableJobModel persistedJob =
                persistedDurableJobRepository.save(
                    new PersistedDurableJobModel(
                        resolvedJobName, JobType.RECURRING, clock.instant()));
            recurringDurableJobRunner.runJobs();

            Optional<PersistedDurableJobModel> executedJob =
                persistedJob != null && persistedJob.getId() != null
                    ? persistedDurableJobRepository.findById(persistedJob.getId())
                    : Optional.empty();

            if (executedJob.isPresent()
                && executedJob.get().getSuccessTime().isEmpty()
                && executedJob.get().getErrorMessage().isPresent()) {
              String errorMsg = "Durable job " + resolvedJobName + " failed.";
              if (isJsonRequest(request)) {
                return internalServerError(Json.newObject().put("error", errorMsg));
              }
              return redirect(controllers.routes.DevToolsController.index())
                  .flashing("error", errorMsg);
            }

            String successMsg = "Ran durable job: " + resolvedJobName;
            if (isJsonRequest(request)) {
              return ok(
                  Json.newObject().put("jobName", resolvedJobName).put("status", "COMPLETED"));
            }
            return redirect(controllers.routes.DevToolsController.index())
                .flashing("success", successMsg);
          } catch (RuntimeException e) {
            log.error("Failed running durable job {} from dev tools", resolvedJobName, e);
            String errorMsg = "Failed to run durable job " + resolvedJobName + ".";
            if (isJsonRequest(request)) {
              return internalServerError(Json.newObject().put("error", errorMsg));
            }
            return redirect(controllers.routes.DevToolsController.index())
                .flashing("error", errorMsg);
          }
        });
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
