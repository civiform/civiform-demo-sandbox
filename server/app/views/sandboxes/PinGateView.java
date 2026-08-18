package views.sandboxes;

import com.google.inject.Inject;
import play.mvc.Http;
import views.BaseView;
import views.shared.BaseViewDeps;

public class PinGateView extends BaseView {
  @Inject
  public PinGateView(BaseViewDeps deps) {
    super(deps, "sandboxes/PinGateView");
  }

  public play.twirl.api.Content render(Http.Request request, PinGateViewModel model) {
    return renderTemplate(request, model);
  }
}
