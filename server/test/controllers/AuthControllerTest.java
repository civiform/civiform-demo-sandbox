package controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.SEE_OTHER;
import static play.test.Helpers.contentAsString;

import com.google.inject.AbstractModule;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;
import services.SandboxService;
import services.InMemorySandboxService;

/**
 * Unit tests for {@link AuthController}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>GET /login — page rendering + already-authenticated redirect
 *   <li>POST /login — correct credentials, wrong password, wrong email, empty fields
 *   <li>POST /login — no password configured (env var unset)
 *   <li>GET /logout — session cleared, redirects to /login
 * </ul>
 */
public class AuthControllerTest extends WithApplication {

  private static final String CORRECT_EMAIL    = "admin@civiform.dev";
  private static final String CORRECT_PASSWORD = "test-secret-pw";

  @Override
  protected Application provideApplication() {
    return new GuiceApplicationBuilder()
        .overrides(new AbstractModule() {
          @Override
          protected void configure() {
            bind(SandboxService.class).to(InMemorySandboxService.class);
          }
        })
        // Inject the test password via config override
        .configure("portal.password", CORRECT_PASSWORD)
        .build();
  }

  // ── GET /login ─────────────────────────────────────────────────────────────

  @Test
  public void login_whenUnauthenticated_returns200WithLoginPage() {
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/login");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    assertThat(contentAsString(result)).containsIgnoringCase("CiviForm Demo Portal");
    assertThat(contentAsString(result)).containsIgnoringCase("Log in to continue");
  }

  @Test
  public void login_whenAlreadyAuthenticated_redirectsToDashboard() {
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/login")
        .session(AuthController.SESSION_KEY, "true");

    Result result = Helpers.route(app, request);

    // Already logged in → skip login page, go straight to dashboard
    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get()).isEqualTo("/sandboxes");
  }

  // ── POST /login — correct credentials ─────────────────────────────────────

  @Test
  public void authenticate_correctCredentials_redirectsToDashboard() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get()).isEqualTo("/sandboxes");
  }

  @Test
  public void authenticate_correctCredentials_setsPortalSessionKey() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    // Session must contain the portal_authed key
    assertThat(result.session().get(AuthController.SESSION_KEY)).isPresent();
    assertThat(result.session().get(AuthController.SESSION_KEY).get()).isEqualTo("true");
  }

  @Test
  public void authenticate_emailCaseInsensitive_succeeds() {
    // The spec says email check is case-insensitive
    Http.RequestBuilder request = loginRequest("ADMIN@CIVIFORM.DEV", CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation().get()).isEqualTo("/sandboxes");
  }

  // ── POST /login — wrong credentials ───────────────────────────────────────

  @Test
  public void authenticate_wrongPassword_returns400WithErrorMessage() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, "wrong-password");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    String body = contentAsString(result);
    assertThat(body).containsIgnoringCase("Incorrect email or password");
    // Email field should be pre-filled with the submitted value
    assertThat(body).contains(CORRECT_EMAIL);
    // No session set on failed login
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
  }

  @Test
  public void authenticate_wrongEmail_returns400WithErrorMessage() {
    Http.RequestBuilder request = loginRequest("notadmin@example.com", CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(contentAsString(result)).containsIgnoringCase("Incorrect email or password");
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
  }

  @Test
  public void authenticate_emptyPassword_returns400() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, "");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
  }

  @Test
  public void authenticate_emptyEmail_returns400() {
    Http.RequestBuilder request = loginRequest("", CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
  }

  // ── POST /login — no password configured ──────────────────────────────────

  @Test
  public void authenticate_noPasswordConfigured_alwaysRejects() {
    // Build a separate app with no portal.password set (empty string → always rejects)
    Application appNoPassword = new GuiceApplicationBuilder()
        .overrides(new AbstractModule() {
          @Override
          protected void configure() {
            bind(SandboxService.class).to(InMemorySandboxService.class);
          }
        })
        .configure("portal.password", "")
        .build();

    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, "");

    Result result = Helpers.route(appNoPassword, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();

    appNoPassword.asScala().stop();
  }

  // ── GET /logout ────────────────────────────────────────────────────────────

  @Test
  public void logout_clearsSessionAndRedirectsToLogin() {
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/logout")
        .session(AuthController.SESSION_KEY, "true");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get()).isEqualTo("/login");
    // Session key must be gone after logout
    assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Http.RequestBuilder loginRequest(String email, String password) {
    return Helpers.fakeRequest()
        .method("POST")
        .uri("/login")
        .bodyForm(com.google.common.collect.ImmutableMap.of(
            "email", email,
            "password", password));
  }
}
