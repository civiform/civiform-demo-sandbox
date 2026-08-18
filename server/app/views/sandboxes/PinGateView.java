package views.sandboxes;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

/** View for the prospect PIN entry gate page at /sandboxes/:id/access. */
public final class PinGateView extends BaseView<PinGateViewModel> {

  @Inject
  public PinGateView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(PinGateViewModel model, Messages messages) {
    return model.getCityName() + " Demo — Access";
  }

  @Override
  protected String pageHeading(PinGateViewModel model, Messages messages) {
    return model.getCityName() + " CiviForm Demo";
  }

  @Override
  protected String pageTemplate() {
    return "sandboxes/PinGateView";
  }
}
