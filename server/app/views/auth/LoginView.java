package views.auth;

import com.google.inject.Inject;
import play.i18n.Messages;
import views.BaseView;
import views.LayoutTemplate;
import views.shared.BaseViewDeps;

import java.util.Optional;

/** View for the {@code GET /login} portal login page. */
public final class LoginView extends BaseView<LoginViewModel> {

  @Inject
  public LoginView(BaseViewDeps baseViewDeps) {
    super(baseViewDeps);
  }

  @Override
  protected String pageTitle(LoginViewModel model, Messages messages) {
    return "Log in — CiviForm Demo Portal";
  }

  @Override
  protected Optional<LayoutTemplate> layoutTemplate() {
    return Optional.of(LayoutTemplate.LOGIN_LAYOUT);
  }

  @Override
  protected String pageTemplate() {
    return "auth/LoginView";
  }
}
