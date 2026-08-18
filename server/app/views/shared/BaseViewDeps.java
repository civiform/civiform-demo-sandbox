package views.shared;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.Inject;
import com.typesafe.config.Config;
import modules.ThymeleafModule;
import org.thymeleaf.TemplateEngine;
import play.Environment;
import play.i18n.MessagesApi;

public record BaseViewDeps(
    TemplateEngine templateEngine,
    ThymeleafModule.PlayThymeleafContextFactory playThymeleafContextFactory,
    MessagesApi messagesApi,
    Environment environment,
    Config config) {

  @Inject
  public BaseViewDeps(
      TemplateEngine templateEngine,
      ThymeleafModule.PlayThymeleafContextFactory playThymeleafContextFactory,
      MessagesApi messagesApi,
      Environment environment,
      Config config) {
    this.templateEngine = checkNotNull(templateEngine);
    this.playThymeleafContextFactory = checkNotNull(playThymeleafContextFactory);
    this.messagesApi = checkNotNull(messagesApi);
    this.environment = checkNotNull(environment);
    this.config = checkNotNull(config);
  }
}
