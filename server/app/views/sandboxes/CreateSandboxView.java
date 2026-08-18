package views.sandboxes;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

public final class CreateSandboxView extends BaseView<CreateSandboxViewModel> {

  @Inject
  public CreateSandboxView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(CreateSandboxViewModel model, Messages messages) {
    return "Create New Demo — CiviForm Demo Portal";
  }

  @Override
  protected String pageHeading(CreateSandboxViewModel model, Messages messages) {
    return "Create New Demo";
  }

  @Override
  protected String pageTemplate() {
    return "sandboxes/CreateSandboxView";
  }
}
