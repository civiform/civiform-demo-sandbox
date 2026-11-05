package views.dev;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

/** Renders the {@code GET /dev} developer debugging tools page. */
public final class DevToolsView extends BaseView<DevToolsViewModel> {

  @Inject
  public DevToolsView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(DevToolsViewModel model, Messages messages) {
    return messages.at("devtools.title") + " - CiviForm Sandbox Builder";
  }

  @Override
  protected String pageHeading(DevToolsViewModel model, Messages messages) {
    return messages.at("devtools.title");
  }

  @Override
  protected String pageTemplate() {
    return "dev/DevToolsView";
  }
}
