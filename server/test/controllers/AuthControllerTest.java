package controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.FORBIDDEN;
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
        // Inject the test password and ensure Auth0 is disabled for password-mode tests
        .configure("portal.password", CORRECT_PASSWORD)
        .configure("auth0.domain", "")
        .configure("auth0.clientId", "")
        .configure("auth0.clientSecret", "")
        .configure("auth0.callbackUrl", "")
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
    assertThat(result.redirectLocation().get())
        .isEqualTo(routes.SandboxController.index().url());
  }

  // ── POST /login — correct credentials ─────────────────────────────────────

  @Test
  public void authenticate_correctCredentials_redirectsToDashboard() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(SEE_OTHER);
    assertThat(result.redirectLocation()).isPresent();
    assertThat(result.redirectLocation().get())
        .isEqualTo(routes.SandboxController.index().url());
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
    assertThat(result.redirectLocation().get())
        .isEqualTo(routes.SandboxController.index().url());
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
    // Failed login must not touch the session (Result.session() is null when untouched)
    assertThat(result.session()).isNull();
  }

  @Test
  public void authenticate_wrongEmail_returns400WithErrorMessage() {
    Http.RequestBuilder request = loginRequest("notadmin@example.com", CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(contentAsString(result)).containsIgnoringCase("Incorrect email or password");
    assertThat(result.session()).isNull();
  }

  @Test
  public void authenticate_emptyPassword_returns400() {
    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, "");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session()).isNull();
  }

  @Test
  public void authenticate_emptyEmail_returns400() {
    Http.RequestBuilder request = loginRequest("", CORRECT_PASSWORD);

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session()).isNull();
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
        .configure("auth0.domain", "")
        .configure("auth0.clientId", "")
        .configure("auth0.clientSecret", "")
        .build();

    Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, "");

    Result result = Helpers.route(appNoPassword, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(result.session()).isNull();

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

  // ── CSRF enforcement ──────────────────────────────────────────────────────

  @Test
  public void authenticate_hasCsrfCheckAnnotation() throws NoSuchMethodException {
    // Structural test: @RequireCSRFCheck must be present on authenticate().
    // Helpers.route() bypasses HTTP filters, so we can't provoke a 403 in a
    // WithApplication test — but we can verify the annotation exists.
    java.lang.reflect.Method authenticate =
        AuthController.class.getMethod("authenticate", Http.Request.class);
    assertThat(authenticate.isAnnotationPresent(
        play.filters.csrf.RequireCSRFCheck.class)).isTrue();
  }

  @Test
  public void csrfFilter_isNotDisabled() {
    // Verify that CSRFFilter is NOT in the disabled-filters list.
    // (It was disabled globally before this PR.)
    java.util.List<String> disabled =
        app.config().getStringList("play.filters.disabled");
    assertThat(disabled).doesNotContain("play.filters.csrf.CSRFFilter");
  }

  // ── Auth0 OIDC flow ───────────────────────────────────────────────────────

  @Test
  public void loginWithAuth0_whenNotConfigured_returns400() {
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/login/auth0");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(BAD_REQUEST);
    assertThat(contentAsString(result)).containsIgnoringCase("Auth0 authentication is not configured");
  }

  @Test
  public void loginWithAuth0_whenConfigured_redirectsToAuthorizeAndSetsStateInSession() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/login/auth0");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(SEE_OTHER);
      assertThat(result.redirectLocation()).isPresent();
      String redirectUrl = result.redirectLocation().get();
      assertThat(redirectUrl).startsWith("https://test-tenant.us.auth0.com/authorize?");
      assertThat(redirectUrl).contains("client_id=test-client-id");
      assertThat(redirectUrl).contains("redirect_uri=http%3A%2F%2Flocalhost%3A9001%2Fcallback");
      assertThat(result.session().get(AuthController.SESSION_STATE_KEY)).isPresent();
      String state = result.session().get(AuthController.SESSION_STATE_KEY).get();
      assertThat(redirectUrl).contains("state=" + state);
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void callback_whenAccessDeniedByAuth0Allowlist_returns403WithErrorMessage() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/callback?error=access_denied&error_description=User+is+not+on+the+allowlist")
          .session(AuthController.SESSION_STATE_KEY, "valid-state");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(FORBIDDEN);
      assertThat(contentAsString(result)).contains("User is not on the allowlist");
      assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
      assertThat(result.session().get(AuthController.SESSION_STATE_KEY)).isEmpty();
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void callback_whenStateMismatch_returns400() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/callback?code=test-code&state=wrong-state")
          .session(AuthController.SESSION_STATE_KEY, "expected-state");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(BAD_REQUEST);
      assertThat(contentAsString(result)).containsIgnoringCase("Invalid authentication state");
      assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void callback_whenValidCode_setsSessionAndRedirectsToDashboard() {
    play.libs.ws.WSClient mockWs = mockAuth0WsClient();
    Application auth0App = buildAuth0App(mockWs);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/callback?code=valid-auth-code&state=valid-state")
          .session(AuthController.SESSION_STATE_KEY, "valid-state");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(SEE_OTHER);
      assertThat(result.redirectLocation()).contains(routes.SandboxController.index().url());
      assertThat(result.session().get(AuthController.SESSION_KEY)).contains("true");
      assertThat(result.session().get(AuthController.SESSION_STATE_KEY)).isEmpty();
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void login_whenAuth0Configured_showsOnlyAuth0ButtonAndHidesPasswordForm() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/login");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(OK);
      String body = contentAsString(result);
      assertThat(body).contains("href=\"/login/auth0\"");
      assertThat(body).doesNotContain("name=\"password\"");
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void login_whenAuth0NotConfigured_showsOnlyPasswordFormAndHidesAuth0Button() {
    Http.RequestBuilder request = Helpers.fakeRequest()
        .method("GET")
        .uri("/login");

    Result result = Helpers.route(app, request);

    assertThat(result.status()).isEqualTo(OK);
    String body = contentAsString(result);
    assertThat(body).contains("name=\"password\"");
    assertThat(body).doesNotContain("href=\"/login/auth0\"");
  }

  @Test
  public void authenticate_whenAuth0Configured_rejectsPasswordLoginEvenWithCorrectCredentials() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = loginRequest(CORRECT_EMAIL, CORRECT_PASSWORD);

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(BAD_REQUEST);
      assertThat(contentAsString(result))
          .containsIgnoringCase("Password login is disabled when Auth0 is enabled");
      assertThat(result.session()).isNull();
    } finally {
      auth0App.asScala().stop();
    }
  }

  @Test
  public void logout_whenAuth0Configured_redirectsToAuth0Logout() {
    Application auth0App = buildAuth0App(null);
    try {
      Http.RequestBuilder request = Helpers.fakeRequest()
          .method("GET")
          .uri("/logout")
          .session(AuthController.SESSION_KEY, "true");

      Result result = Helpers.route(auth0App, request);

      assertThat(result.status()).isEqualTo(SEE_OTHER);
      assertThat(result.redirectLocation()).isPresent();
      assertThat(result.redirectLocation().get())
          .startsWith("https://test-tenant.us.auth0.com/v2/logout?client_id=test-client-id&returnTo=");
      assertThat(result.session().get(AuthController.SESSION_KEY)).isEmpty();
    } finally {
      auth0App.asScala().stop();
    }
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

  private static Application buildAuth0App(play.libs.ws.WSClient mockWs) {
    return new GuiceApplicationBuilder()
        .overrides(new AbstractModule() {
          @Override
          protected void configure() {
            bind(SandboxService.class).to(InMemorySandboxService.class);
            if (mockWs != null) {
              bind(play.libs.ws.WSClient.class).toInstance(mockWs);
            }
          }
        })
        .configure("portal.password", CORRECT_PASSWORD)
        .configure("app.baseUrl", "http://localhost:9001")
        .configure("auth0.domain", "test-tenant.us.auth0.com")
        .configure("auth0.clientId", "test-client-id")
        .configure("auth0.clientSecret", "test-client-secret")
        .configure("auth0.callbackUrl", "")
        .build();
  }

  private static play.libs.ws.WSClient mockAuth0WsClient() {
    play.libs.ws.WSClient wsClient = org.mockito.Mockito.mock(play.libs.ws.WSClient.class);
    play.libs.ws.WSRequest tokenReq = org.mockito.Mockito.mock(play.libs.ws.WSRequest.class);
    play.libs.ws.WSResponse tokenResp = org.mockito.Mockito.mock(play.libs.ws.WSResponse.class);

    com.fasterxml.jackson.databind.node.ObjectNode tokenJson = play.libs.Json.newObject();
    tokenJson.put("access_token", "mock-access-token");

    org.mockito.Mockito.when(tokenResp.getStatus()).thenReturn(OK);
    org.mockito.Mockito.when(tokenResp.asJson()).thenReturn(tokenJson);
    org.mockito.Mockito.when(tokenReq.post(org.mockito.ArgumentMatchers.any(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(tokenResp));
    org.mockito.Mockito.when(wsClient.url("https://test-tenant.us.auth0.com/oauth/token"))
        .thenReturn(tokenReq);

    return wsClient;
  }
}
