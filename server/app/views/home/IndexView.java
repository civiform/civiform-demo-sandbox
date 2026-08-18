package views.home;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.shared.BaseViewDeps;

public final class IndexView extends BaseView<IndexViewModel> {

  @Inject
  public IndexView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(IndexViewModel model, Messages messages) {
    return "Civiform Sandbox Builder - Dashboard";
  }

  @Override
  protected String pageHeading(IndexViewModel model, Messages messages) {
    return "Civiform Sandbox Builder";
  }

  @Override
  protected String pageTemplate() {
    return "home/IndexView";
  }
}
