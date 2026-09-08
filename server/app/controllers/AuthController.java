package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.Inject;
import com.typesafe.config.Config;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import views.auth.LoginView;
import views.auth.LoginViewModel;

/**
 * Handles portal authentication for the CiviForm Demo Portal.
 *
 * <p>MVP implementation: single shared account. Credentials are:
 * <ul>
 *   <li>Email: {@code admin@civiform.dev} (hardcoded)
 *   <li>Password: value of {@code DEMO_PORTAL_PASSWORD} env var (via {@code portal.password} in
 *       application.conf)
 * </ul>
 *
 * <p>Sprint 6: replace with real Google OAuth.
 */
public final class AuthController extends Controller {

  static final String SESSION_KEY = "portal_authed";
  static final String ADMIN_EMAIL = "admin@civiform.dev";

  private final LoginView loginView;
  private final FormFactory formFactory;
  private final String portalPassword;

  @Inject
  public AuthController(LoginView loginView, FormFactory formFactory, Config config) {
    this.loginView = checkNotNull(loginView);
    this.formFactory = checkNotNull(formFactory);
    // Falls back to empty string if env var is unset — login will always fail
    this.portalPassword = config.hasPath("portal.password") ? config.getString("portal.password") : "";
  }

  /** GET /login — show the login page. Redirects to dashboard if already authenticated. */
  public Result login(Http.Request request) {
    if (isAuthenticated(request)) {
      return redirect(controllers.routes.SandboxController.index());
    }
    return ok(loginView.render(request, LoginViewModel.builder().build()))
        .as("text/html");
  }

  /**
   * POST /login — validate credentials.
   *
   * <ul>
   *   <li>Correct → set {@code portal_authed} session key, redirect to {@code /sandboxes}
   *   <li>Wrong → re-render login with error message (email field pre-filled)
   * </ul>
   */
  public Result authenticate(Http.Request request) {
    DynamicForm form = formFactory.form().bindFromRequest(request);
    String email = orEmpty(form.get("email"));
    String password = orEmpty(form.get("password"));

    boolean credentialsValid =
        ADMIN_EMAIL.equalsIgnoreCase(email.trim())
            && !portalPassword.isEmpty()
            && portalPassword.equals(password);

    if (credentialsValid) {
      return redirect(controllers.routes.SandboxController.index())
          .addingToSession(request, SESSION_KEY, "true");
    }

    LoginViewModel model = LoginViewModel.builder()
        .email(email)
        .error("Incorrect email or password. Please try again.")
        .build();
    return badRequest(loginView.render(request, model)).as("text/html");
  }

  /** GET /logout — clear session, redirect to login page. */
  public Result logout(Http.Request request) {
    return redirect(controllers.routes.AuthController.login())
        .withNewSession();
  }

  /** Returns true if the request carries a valid portal session cookie. */
  public static boolean isAuthenticated(Http.Request request) {
    return "true".equals(request.session().get(SESSION_KEY).orElse(null));
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
