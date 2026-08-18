package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import models.SandboxInstance;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.SandboxService;
import views.sandboxes.SandboxDetailsView;
import views.sandboxes.SandboxDetailsViewModel;
import views.sandboxes.SandboxListView;
import views.sandboxes.SandboxListViewModel;

public class SandboxController extends Controller {

  private final SandboxService sandboxService;
  private final SandboxListView listView;
  private final SandboxDetailsView detailsView;
  private final FormFactory formFactory;

  @Inject
  public SandboxController(
      SandboxService sandboxService,
      SandboxListView listView,
      SandboxDetailsView detailsView,
      FormFactory formFactory) {
    this.sandboxService = checkNotNull(sandboxService);
    this.listView = checkNotNull(listView);
    this.detailsView = checkNotNull(detailsView);
    this.formFactory = checkNotNull(formFactory);
  }

  public CompletionStage<Result> index(Http.Request request) {
    return sandboxService.listSandboxes().thenApply(sandboxes -> {
      if (request.accepts("application/json") && !request.accepts("text/html")) {
        return ok(Json.toJson(sandboxes));
      }
      SandboxListViewModel model = SandboxListViewModel.builder()
          .sandboxes(sandboxes)
          .build();
      return ok(listView.render(request, model)).as("text/html");
    });
  }

  public CompletionStage<Result> create(Http.Request request) {
    String name;
    String version;
    String adminEmail;
    String notes;

    if (request.hasBody() && request.body().asJson() != null) {
      JsonNode json = request.body().asJson();
      name = json.has("name") ? json.get("name").asText() : "Civiform Demo";
      version = json.has("version") ? json.get("version").asText() : "latest";
      adminEmail = json.has("adminEmail") ? json.get("adminEmail").asText() : "admin@civiform.dev";
      notes = json.has("notes") ? json.get("notes").asText() : "";
    } else {
      DynamicForm form = formFactory.form().bindFromRequest(request);
      name = form.get("name") != null ? form.get("name") : "Civiform Demo";
      version = form.get("version") != null ? form.get("version") : "latest";
      adminEmail = form.get("adminEmail") != null ? form.get("adminEmail") : "admin@civiform.dev";
      notes = form.get("notes") != null ? form.get("notes") : "";
    }

    return sandboxService.createSandbox(name, version, adminEmail, notes)
        .thenApply(instance -> {
          if (request.accepts("application/json") && !request.accepts("text/html")) {
            return created(Json.toJson(instance));
          }
          return redirect(controllers.routes.HomeController.index());
        });
  }

  public CompletionStage<Result> show(Http.Request request, String id) {
    return sandboxService.getSandbox(id).thenApply(maybeSandbox -> {
      if (maybeSandbox.isEmpty()) {
        return notFound("Sandbox not found: " + id);
      }
      SandboxInstance sandbox = maybeSandbox.get();
      if (request.accepts("application/json") && !request.accepts("text/html")) {
        return ok(Json.toJson(sandbox));
      }
      SandboxDetailsViewModel model = SandboxDetailsViewModel.builder()
          .sandbox(sandbox)
          .build();
      return ok(detailsView.render(request, model)).as("text/html");
    });
  }

  public CompletionStage<Result> delete(Http.Request request, String id) {
    return sandboxService.deleteSandbox(id).thenApply(deleted -> {
      if (request.accepts("application/json") && !request.accepts("text/html")) {
        return ok(Json.newObject().put("deleted", deleted));
      }
      return redirect(controllers.routes.HomeController.index());
    });
  }
}
