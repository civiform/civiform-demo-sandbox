package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import models.SandboxInstance;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.SandboxService;
import views.sandboxes.PinGateView;
import views.sandboxes.PinGateViewModel;
import views.sandboxes.SandboxDetailsView;
import views.sandboxes.SandboxDetailsViewModel;
import views.sandboxes.SandboxListView;
import views.sandboxes.SandboxListViewModel;

public class SandboxController extends Controller {

  private final SandboxService sandboxService;
  private final SandboxListView listView;
  private final SandboxDetailsView detailsView;
  private final PinGateView pinGateView;
  private final FormFactory formFactory;

  @Inject
  public SandboxController(
      SandboxService sandboxService,
      SandboxListView listView,
      SandboxDetailsView detailsView,
      PinGateView pinGateView,
      FormFactory formFactory) {
    this.sandboxService = checkNotNull(sandboxService);
    this.listView = checkNotNull(listView);
    this.detailsView = checkNotNull(detailsView);
    this.pinGateView = checkNotNull(pinGateView);
    this.formFactory = checkNotNull(formFactory);
  }

  /** GET /sandboxes — list all sandboxes (JSON or HTML). */
  public CompletionStage<Result> index(Http.Request request) {
    return sandboxService.listSandboxes().thenApply(sandboxes -> {
      if (isJsonRequest(request)) {
        return ok(Json.toJson(sandboxes));
      }
      SandboxListViewModel model = SandboxListViewModel.builder()
          .sandboxes(sandboxes)
          .build();
      return ok(listView.render(request, model)).as("text/html");
    });
  }

  /**
   * POST /sandboxes — create a new sandbox.
   * Redirects to the sandbox detail page (BE-10: was incorrectly redirecting to home).
   */
  public CompletionStage<Result> create(Http.Request request) {
    String cityName;
    String version;
    String adminEmail;
    String notes;

    if (request.hasBody() && request.body().asJson() != null) {
      JsonNode json = request.body().asJson();
      cityName   = json.has("cityName")   ? json.get("cityName").asText()   : "Burlington, VT";
      version    = json.has("version")    ? json.get("version").asText()    : "latest";
      adminEmail = json.has("adminEmail") ? json.get("adminEmail").asText() : "";
      notes      = json.has("notes")      ? json.get("notes").asText()      : "";
    } else {
      DynamicForm form = formFactory.form().bindFromRequest(request);
      cityName   = orDefault(form.get("cityName"),   "Burlington, VT");
      version    = orDefault(form.get("version"),    "latest");
      adminEmail = orDefault(form.get("adminEmail"), "");
      notes      = orDefault(form.get("notes"),      "");
    }

    return sandboxService.createSandbox(cityName, version, adminEmail, notes)
        .thenApply(instance -> {
          if (isJsonRequest(request)) {
            return created(Json.toJson(instance));
          }
          // Redirect to detail page — PIN is immediately visible there
          // Note: Request param NOT included in reverse route call
          return redirect(controllers.routes.SandboxController.show(instance.getId()));
        });
  }

  /** GET /sandboxes/:id — detail page or JSON. */
  public CompletionStage<Result> show(Http.Request request, String id) {
    return sandboxService.getSandbox(id).thenApply(maybeSandbox -> {
      if (maybeSandbox.isEmpty()) {
        return notFound("Sandbox not found: " + id);
      }
      SandboxInstance sandbox = maybeSandbox.get();
      if (isJsonRequest(request)) {
        return ok(Json.toJson(sandbox));
      }
      SandboxDetailsViewModel model = SandboxDetailsViewModel.builder()
          .sandbox(sandbox)
          .build();
      return ok(detailsView.render(request, model)).as("text/html");
    });
  }

  /**
   * GET /sandboxes/:id/status — HTMX partial: status badge only, no layout wrapper.
   * Returns bare HTML that HTMX swaps in. Stops self-polling once RUNNING or FAILED.
   * Auto-navigates to detail page when RUNNING.
   */
  public CompletionStage<Result> statusFragment(Http.Request request, String id) {
    return sandboxService.getSandbox(id).thenApply(maybeSandbox -> {
      if (maybeSandbox.isEmpty()) {
        return notFound();
      }
      SandboxInstance sandbox = maybeSandbox.get();
      String status = sandbox.getStatus().name();
      boolean isRunning = "RUNNING".equals(status);
      boolean isFailed  = "FAILED".equals(status);
      boolean isDone    = isRunning || isFailed;

      String badgeClass = isRunning ? "cf-badge-active"
          : isFailed ? "cf-badge-error"
          : "cf-badge-pending";

      // When still provisioning, HTMX will replace this element every 3s
      String hxAttrs = isDone ? "" :
          " hx-get=\"/sandboxes/" + id + "/status\""
          + " hx-trigger=\"every 3s\""
          + " hx-swap=\"outerHTML\"";

      // Once running, redirect the whole page to the detail view
      String redirectScript = isRunning
          ? "<script>window.location.href='/sandboxes/" + id + "'</script>"
          : "";

      String html = "<span class=\"" + badgeClass + "\"" + hxAttrs + ">"
          + status + "</span>" + redirectScript;

      return ok(html).as("text/html");
    });
  }

  /** GET /sandboxes/:id/access — PIN gate page for prospects. */
  public CompletionStage<Result> pinGate(Http.Request request, String id) {
    return sandboxService.getSandbox(id).thenApply(maybeSandbox -> {
      if (maybeSandbox.isEmpty()) {
        return notFound("Sandbox not found: " + id);
      }
      PinGateViewModel model = PinGateViewModel.builder()
          .sandboxId(id)
          .cityName(maybeSandbox.get().getCityName())
          .error(null)
          .build();
      return ok(pinGateView.render(request, model)).as("text/html");
    });
  }

  /**
   * POST /sandboxes/:id/access — validates the 6-digit PIN.
   * Correct PIN → redirects to live CiviForm URL.
   * Wrong PIN → re-renders PIN gate with error message.
   */
  public CompletionStage<Result> validateAccess(Http.Request request, String id) {
    DynamicForm form = formFactory.form().bindFromRequest(request);
    String pin = orDefault(form.get("pin"), "");

    return sandboxService.validatePin(id, pin).thenCompose(maybeSandbox -> {
      if (maybeSandbox.isPresent()) {
        // Correct PIN — redirect prospect to live CiviForm instance
        return CompletableFuture.completedFuture(
            redirect(maybeSandbox.get().getUrl()));
      }
      // Wrong PIN — re-render gate with error, need cityName for the view
      return sandboxService.getSandbox(id).thenApply(ms -> {
        PinGateViewModel model = PinGateViewModel.builder()
            .sandboxId(id)
            .cityName(ms.map(SandboxInstance::getCityName).orElse(""))
            .error("Incorrect PIN. Please try again.")
            .build();
        return badRequest(pinGateView.render(request, model)).as("text/html");
      });
    });
  }

  /** POST /sandboxes/:id/delete — destroys a sandbox and redirects to list. */
  public CompletionStage<Result> delete(Http.Request request, String id) {
    return sandboxService.deleteSandbox(id).thenApply(deleted -> {
      if (isJsonRequest(request)) {
        return ok(Json.newObject().put("deleted", deleted));
      }
      return redirect(controllers.routes.SandboxController.index());
    });
  }

  private boolean isJsonRequest(Http.Request request) {
    return request.accepts("application/json") && !request.accepts("text/html");
  }

  private String orDefault(String value, String defaultValue) {
    return (value != null && !value.isBlank()) ? value : defaultValue;
  }
}
