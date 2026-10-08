package controllers;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.filters.csrf.AddCSRFToken;
import play.filters.csrf.RequireCSRFCheck;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import views.auth.LoginView;
import views.auth.LoginViewModel;

/**
 * Handles portal authentication for the CiviForm Demo Portal.
 *
 * <p>Supports two authentication modes:
 * <ul>
 *   <li><b>Auth0 OIDC</b> (primary): Enabled when {@code auth0.domain}, {@code auth0.clientId},
 *       and {@code auth0.clientSecret} are configured (tenant: {@code civiform-sandbox-builder}).
 *       User allowlisting is managed in Auth0 via the {@code "Sandbox builder users"} role and
 *       enforced by a Post-Login Action:
 *       <pre>{@code
 *       exports.onExecutePostLogin = async (event, api) => {
 *         // Define the required role(s) to allow login
 *         const allowedRoles = ['Sandbox builder users'];
 *
 *         // Check if the user has any of the roles
 *         const userRoles = event.authorization?.roles || [];
 *         const hasRequiredRole = userRoles.some(role => allowedRoles.includes(role));
 *
 *         // If the user does not have the required role, deny access
 *         if (!hasRequiredRole) {
 *           api.access.deny('Access denied: You do not have the required role to log in.');
 *         }
 *       };
 *       }</pre>
 *       When access is denied, Auth0 redirects to {@code /callback?error=access_denied}, which this
 *       controller surfaces on the login page with HTTP 403.
 *   <li><b>Local password fallback</b>: Single shared account ({@code admin@civiform.dev} +
 *       {@code DEMO_PORTAL_PASSWORD} via {@code portal.password} in {@code application.conf}).
 * </ul>
 */
public final class AuthController extends Controller {

  static final String SESSION_KEY = "portal_authed";
  static final String SESSION_STATE_KEY = "auth0_state";
  static final String ADMIN_EMAIL = "admin@civiform.dev";

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final LoginView loginView;
  private final FormFactory formFactory;
  private final WSClient wsClient;
  private final String portalPassword;
  private final String auth0Domain;
  private final String auth0ClientId;
  private final String auth0ClientSecret;
  private final String auth0CallbackUrl;
  private final String appBaseUrl;

  @Inject
  public AuthController(
      LoginView loginView, FormFactory formFactory, WSClient wsClient, Config config) {
    this.loginView = checkNotNull(loginView);
    this.formFactory = checkNotNull(formFactory);
    this.wsClient = checkNotNull(wsClient);
    checkNotNull(config);

    this.portalPassword = configOrEmpty(config, "portal.password");
    this.auth0Domain = normalizeDomain(configOrEmpty(config, "auth0.domain"));
    this.auth0ClientId = configOrEmpty(config, "auth0.clientId");
    this.auth0ClientSecret = configOrEmpty(config, "auth0.clientSecret");
    this.appBaseUrl = stripTrailingSlash(configOrDefault(config, "app.baseUrl", "http://localhost:9001"));

    String configuredCallback = configOrEmpty(config, "auth0.callbackUrl");
    this.auth0CallbackUrl =
        configuredCallback.isEmpty() ? this.appBaseUrl + "/callback" : configuredCallback;
  }

  /** GET /login — show the login page. Redirects to dashboard if already authenticated. */
  @AddCSRFToken
  public Result login(Http.Request request) {
    if (isAuthenticated(request)) {
      return redirect(controllers.routes.SandboxController.index());
    }
    return ok(loginView.render(request, buildModel(null, null)))
        .as("text/html");
  }

  /**
   * POST /login — validate development email/password credentials.
   *
   * <p>Only active when Auth0 OIDC is not configured.
   *
   * <ul>
   *   <li>Correct → set {@code portal_authed} session key, redirect to the dashboard
   *   <li>Wrong → re-render login with error message (email field pre-filled)
   * </ul>
   */
  @RequireCSRFCheck
  public Result authenticate(Http.Request request) {
    if (isAuth0Configured()) {
      return badRequest(
              loginView.render(
                  request,
                  buildModel(null, "Password login is disabled when Auth0 is enabled.")))
          .as("text/html");
    }

    DynamicForm form = formFactory.form().bindFromRequest(request);
    String email = orEmpty(form.get("email"));
    String password = orEmpty(form.get("password"));

    boolean credentialsValid =
        ADMIN_EMAIL.equalsIgnoreCase(email.trim())
            && !portalPassword.isEmpty()
            && constantTimeEquals(portalPassword, password);

    if (credentialsValid) {
      return redirect(controllers.routes.SandboxController.index())
          .addingToSession(request, SESSION_KEY, "true");
    }

    LoginViewModel model = buildModel(email, "Incorrect email or password. Please try again.");
    return badRequest(loginView.render(request, model)).as("text/html");
  }

  /**
   * GET /login/auth0 — initiate the Auth0 OIDC Authorization Code flow.
   *
   * <p>Generates a cryptographic CSRF state token, stores it in the Play session, and redirects
   * the user to the Auth0 Universal Login {@code /authorize} endpoint.
   */
  @AddCSRFToken
  public Result loginWithAuth0(Http.Request request) {
    if (isAuthenticated(request)) {
      return redirect(controllers.routes.SandboxController.index());
    }
    if (!isAuth0Configured()) {
      return badRequest(
              loginView.render(request, buildModel(null, "Auth0 authentication is not configured.")))
          .as("text/html");
    }

    String state = generateStateToken();
    String authorizeUrl =
        String.format(
            "https://%s/authorize?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&state=%s",
            auth0Domain,
            urlEncode(auth0ClientId),
            urlEncode(auth0CallbackUrl),
            urlEncode("openid profile email"),
            urlEncode(state));

    return redirect(authorizeUrl).addingToSession(request, SESSION_STATE_KEY, state);
  }

  /**
   * GET /callback — handle the OIDC redirect from Auth0.
   *
   * <ul>
   *   <li>If Auth0 denies access (e.g. user not on the Auth0 allowlist), surfaces the denial
   *       reason with HTTP 403.
   *   <li>Verifies the OAuth {@code state} parameter against the session cookie in constant time.
   *   <li>Exchanges the authorization {@code code} for tokens at {@code /oauth/token} and
   *       establishes the authenticated portal session.
   * </ul>
   */
  @AddCSRFToken
  public CompletionStage<Result> callback(Http.Request request) {
    if (!isAuth0Configured()) {
      return CompletableFuture.completedFuture(
          badRequest(
                  loginView.render(
                      request, buildModel(null, "Auth0 authentication is not configured.")))
              .as("text/html"));
    }

    String error = orEmpty(request.getQueryString("error"));
    if (!error.isEmpty()) {
      String errorDescription = orEmpty(request.getQueryString("error_description")).trim();
      String message =
          errorDescription.isEmpty()
              ? "Access denied. Your account is not authorized to access this portal."
              : errorDescription;
      Result errorResult =
          "access_denied".equalsIgnoreCase(error)
              ? forbidden(loginView.render(request, buildModel(null, message)))
              : badRequest(loginView.render(request, buildModel(null, message)));
      return CompletableFuture.completedFuture(
          errorResult.as("text/html").removingFromSession(request, SESSION_STATE_KEY));
    }

    String stateParam = orEmpty(request.getQueryString("state"));
    String sessionState = request.session().get(SESSION_STATE_KEY).orElse("");
    if (stateParam.isEmpty()
        || sessionState.isEmpty()
        || !constantTimeEquals(sessionState, stateParam)) {
      return CompletableFuture.completedFuture(
          badRequest(
                  loginView.render(
                      request,
                      buildModel(
                          null, "Invalid authentication state. Please try logging in again.")))
              .as("text/html")
              .removingFromSession(request, SESSION_STATE_KEY));
    }

    String code = orEmpty(request.getQueryString("code")).trim();
    if (code.isEmpty()) {
      return CompletableFuture.completedFuture(
          badRequest(
                  loginView.render(
                      request,
                      buildModel(null, "Missing authorization code. Please try logging in again.")))
              .as("text/html")
              .removingFromSession(request, SESSION_STATE_KEY));
    }

    ObjectNode tokenRequest = Json.newObject();
    tokenRequest.put("grant_type", "authorization_code");
    tokenRequest.put("client_id", auth0ClientId);
    tokenRequest.put("client_secret", auth0ClientSecret);
    tokenRequest.put("code", code);
    tokenRequest.put("redirect_uri", auth0CallbackUrl);

    String tokenUrl = String.format("https://%s/oauth/token", auth0Domain);

    return wsClient
        .url(tokenUrl)
        .post(tokenRequest)
        .thenApply(
            tokenResponse -> {
              if (tokenResponse.getStatus() != Http.Status.OK) {
                return badRequest(
                        loginView.render(
                            request,
                            buildModel(
                                null,
                                "Authentication failed: unable to verify authorization code.")))
                    .as("text/html")
                    .removingFromSession(request, SESSION_STATE_KEY);
              }

              JsonNode tokenJson = tokenResponse.asJson();
              String accessToken = tokenJson.path("access_token").asText("").trim();
              if (accessToken.isEmpty()) {
                return badRequest(
                        loginView.render(
                            request,
                            buildModel(null, "Authentication failed: missing access token.")))
                    .as("text/html")
                    .removingFromSession(request, SESSION_STATE_KEY);
              }

              return redirect(controllers.routes.SandboxController.index())
                  .addingToSession(request, SESSION_KEY, "true")
                  .removingFromSession(request, SESSION_STATE_KEY);
            })
        .exceptionally(
            ex ->
                badRequest(
                        loginView.render(
                            request,
                            buildModel(null, "Authentication service error. Please try again.")))
                    .as("text/html")
                    .removingFromSession(request, SESSION_STATE_KEY));
  }

  /**
   * GET /logout — clear session and redirect to login page (or Auth0 logout endpoint when Auth0 is
   * configured).
   */
  public Result logout(Http.Request request) {
    if (isAuth0Configured()) {
      String returnTo = appBaseUrl + controllers.routes.AuthController.login().url();
      String auth0LogoutUrl =
          String.format(
              "https://%s/v2/logout?client_id=%s&returnTo=%s",
              auth0Domain, urlEncode(auth0ClientId), urlEncode(returnTo));
      return redirect(auth0LogoutUrl).withNewSession();
    }
    return redirect(controllers.routes.AuthController.login())
        .withNewSession();
  }

  /** Returns true if the request carries a valid portal session cookie. */
  public static boolean isAuthenticated(Http.Request request) {
    return "true".equals(request.session().get(SESSION_KEY).orElse(null));
  }

  /** Returns true when all required Auth0 OIDC configuration settings are present. */
  boolean isAuth0Configured() {
    return !auth0Domain.isEmpty() && !auth0ClientId.isEmpty() && !auth0ClientSecret.isEmpty();
  }

  private LoginViewModel buildModel(String email, String error) {
    return LoginViewModel.builder()
        .email(email)
        .error(error)
        .auth0Enabled(isAuth0Configured())
        .build();
  }

  private static String generateStateToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String configOrEmpty(Config config, String path) {
    return config.hasPath(path) ? orEmpty(config.getString(path)).trim() : "";
  }

  private static String configOrDefault(Config config, String path, String defaultValue) {
    String val = configOrEmpty(config, path);
    return val.isEmpty() ? defaultValue : val;
  }

  private static String normalizeDomain(String domain) {
    String normalized = domain.trim();
    if (normalized.startsWith("https://")) {
      normalized = normalized.substring("https://".length());
    } else if (normalized.startsWith("http://")) {
      normalized = normalized.substring("http://".length());
    }
    return stripTrailingSlash(normalized);
  }

  private static String stripTrailingSlash(String url) {
    String result = url.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
