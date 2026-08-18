package views;

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import modules.ThymeleafModule;
import org.thymeleaf.TemplateEngine;
import play.Environment;
import play.i18n.Messages;
import play.i18n.MessagesApi;
import play.mvc.Http;
import views.shared.BaseViewDeps;
import views.shared.LayoutParams;
import views.shared.ScriptElementSettings;
import views.shared.TemplateGlobals;

/**
 * Base view for rendering Thymeleaf templates in the Civiform architecture.
 *
 * @param <TModel> Type of model extending BaseViewModel
 */
public abstract class BaseView<TModel extends BaseViewModel> {
  private final TemplateEngine templateEngine;
  private final ThymeleafModule.PlayThymeleafContextFactory playThymeleafContextFactory;
  private final MessagesApi messagesApi;
  private final Environment environment;

  public BaseView(BaseViewDeps baseViewDeps) {
    this.templateEngine = baseViewDeps.templateEngine();
    this.playThymeleafContextFactory = baseViewDeps.playThymeleafContextFactory();
    this.messagesApi = baseViewDeps.messagesApi();
    this.environment = baseViewDeps.environment();
  }

  protected String pageTitle(TModel model, Messages messages) {
    return "Civiform Sandbox Builder";
  }

  protected String pageHeading(TModel model, Messages messages) {
    return pageTitle(model, messages);
  }

  protected Optional<String> pageIntro(TModel model, Messages messages) {
    return Optional.empty();
  }

  protected abstract String pageTemplate();

  protected Optional<LayoutTemplate> layoutTemplate() {
    return Optional.of(LayoutTemplate.MAIN_LAYOUT);
  }

  protected LayoutType layoutType() {
    return LayoutType.CONTENT_ONLY;
  }

  protected ImmutableList<String> getSiteStylesheets() {
    return ImmutableList.of(
        "/assets/dist/uswds_css.min.css",
        "/assets/dist/tailwind.min.css"
    );
  }

  protected ImmutableList<String> getPageStylesheets() {
    return ImmutableList.of();
  }

  protected ImmutableList<ScriptElementSettings> getSiteHeadScripts() {
    return ImmutableList.of(
        ScriptElementSettings.builder()
            .src("/assets/dist/uswdsinit_js.bundle.js")
            .type("text/javascript")
            .build()
    );
  }

  protected ImmutableList<ScriptElementSettings> getSiteBodyScripts() {
    return ImmutableList.of(
        ScriptElementSettings.builder()
            .src("/assets/dist/uswds.min.js")
            .type("text/javascript")
            .build(),
        ScriptElementSettings.builder()
            .src("/assets/dist/main.bundle.js")
            .type("module")
            .build()
    );
  }

  public final String render(Http.Request request, TModel model) {
    Messages messages = messagesApi.preferred(request);
    ThymeleafModule.PlayThymeleafContext context = playThymeleafContextFactory.create(request);

    ImmutableList<String> allStylesheets = ImmutableList.<String>builder()
        .addAll(getSiteStylesheets())
        .addAll(getPageStylesheets())
        .build();

    context.setVariable(
        "layoutParams",
        LayoutParams.builder()
            .pageTemplate(pageTemplate())
            .layoutType(layoutType())
            .favicon("/assets/images/favicon.ico")
            .stylesheets(allStylesheets)
            .headScripts(getSiteHeadScripts())
            .bodyScripts(getSiteBodyScripts())
            .build());

    context.setVariable(
        "templateGlobals",
        TemplateGlobals.builder()
            .pageTitle(pageTitle(model, messages))
            .pageHeading(pageHeading(model, messages))
            .pageIntro(pageIntro(model, messages))
            .cspNonce(CspUtil.getNonce(request))
            .csrfToken("")
            .isDev(environment.isDev())
            .build());

    context.setVariable("view", this);
    context.setVariable("model", model);

    if (layoutTemplate().isPresent()) {
      return templateEngine.process(layoutTemplate().get().getPath(), context);
    }

    return templateEngine.process(pageTemplate(), context);
  }
}
