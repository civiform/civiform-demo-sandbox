package views.sandboxes;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import java.util.Optional;
import play.i18n.Messages;
import views.BaseView;
import views.LayoutTemplate;
import views.shared.BaseViewDeps;
import views.shared.ScriptElementSettings;

/**
 * Fullscreen view that renders a live CiviForm sandbox inside an iframe,
 * topped by a persistent demo banner with city name, days remaining, and
 * a role switcher.
 *
 * <p>Uses NO layout template — this page has no portal header/footer.
 * It is the "end-user" view shared with prospects after PIN validation.
 */
public final class DemoWrapperView extends BaseView<DemoWrapperViewModel> {

  @Inject
  public DemoWrapperView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(DemoWrapperViewModel model, Messages messages) {
    return "DEMO: " + model.getCityName();
  }

  @Override
  protected String pageHeading(DemoWrapperViewModel model, Messages messages) {
    return model.getCityName();
  }

  @Override
  protected String pageTemplate() {
    return "sandboxes/DemoWrapperView";
  }

  /**
   * No layout — this page renders its own full HTML document.
   * The demo wrapper is a standalone fullscreen page without portal chrome.
   */
  @Override
  protected Optional<LayoutTemplate> layoutTemplate() {
    return Optional.empty();
  }

  /** No external stylesheets needed — all styles are inline in the template. */
  @Override
  protected ImmutableList<String> getSiteStylesheets() {
    return ImmutableList.of();
  }

  @Override
  protected ImmutableList<ScriptElementSettings> getSiteHeadScripts() {
    return ImmutableList.of();
  }

  @Override
  protected ImmutableList<ScriptElementSettings> getSiteBodyScripts() {
    return ImmutableList.of();
  }
}
