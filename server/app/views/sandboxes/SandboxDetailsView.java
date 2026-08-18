package views.sandboxes;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

public final class SandboxDetailsView extends BaseView<SandboxDetailsViewModel> {

  @Inject
  public SandboxDetailsView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(SandboxDetailsViewModel model, Messages messages) {
    return model.getSandbox().getName() + " - Sandbox Details";
  }

  @Override
  protected String pageHeading(SandboxDetailsViewModel model, Messages messages) {
    return model.getSandbox().getName();
  }

  @Override
  protected String pageTemplate() {
    return "sandboxes/SandboxDetailsView";
  }
}
