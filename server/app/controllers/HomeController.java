package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.Inject;
import java.util.concurrent.CompletionStage;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.SandboxService;
import views.home.IndexView;
import views.home.IndexViewModel;

public class HomeController extends Controller {

  private final IndexView indexView;
  private final SandboxService sandboxService;

  @Inject
  public HomeController(IndexView indexView, SandboxService sandboxService) {
    this.indexView = checkNotNull(indexView);
    this.sandboxService = checkNotNull(sandboxService);
  }

  public CompletionStage<Result> index(Http.Request request) {
    return sandboxService.listSandboxes().thenApply(sandboxes -> {
      int running = (int) sandboxes.stream()
          .filter(s -> s.getStatus() == models.SandboxStatus.RUNNING)
          .count();

      IndexViewModel model = IndexViewModel.builder()
          .activeSandboxes(sandboxes)
          .totalSandboxes(sandboxes.size())
          .runningSandboxes(running)
          .appVersion("v0.1.0-alpha")
          .build();

      return ok(indexView.render(request, model)).as("text/html");
    });
  }
}
