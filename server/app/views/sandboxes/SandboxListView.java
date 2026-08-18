package views.sandboxes;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

public final class SandboxListView extends BaseView<SandboxListViewModel> {

  @Inject
  public SandboxListView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(SandboxListViewModel model, Messages messages) {
    return "All Sandboxes - Civiform Sandbox Builder";
  }

  @Override
  protected String pageHeading(SandboxListViewModel model, Messages messages) {
    return "Sandbox Environments";
  }

  @Override
  protected String pageTemplate() {
    return "sandboxes/SandboxListView";
  }
}
