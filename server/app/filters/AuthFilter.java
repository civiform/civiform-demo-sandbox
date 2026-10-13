package filters;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import controllers.AuthController;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.pekko.stream.Materializer;
import play.mvc.Filter;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

/**
 * HTTP filter that enforces portal authentication across all endpoints by default,
 * with an explicit allowlist of public endpoints (login/logout, health checks,
 * static assets, and prospect-facing PIN gate / demo wrapper routes).
 */
public final class AuthFilter extends Filter {

  /** Exact paths that do not require portal authentication. */
  private static final ImmutableSet<String> PUBLIC_PATHS =
      ImmutableSet.of("/login", "/login/auth0", "/callback", "/logout", "/health", "/ready");

  /** Path prefixes that do not require portal authentication. */
  private static final ImmutableSet<String> PUBLIC_PREFIXES =
      ImmutableSet.of("/assets/");

  /**
   * Prospect-facing sandbox route that does not require portal admin login:
   * {@code /sandboxes/:id/access} — PIN entry gate (GET and POST).
   */
  private static final Pattern PUBLIC_SANDBOX_PATTERN =
      Pattern.compile("^/sandboxes/[^/]+/access$");

  @Inject
  public AuthFilter(Materializer mat) {
    super(checkNotNull(mat));
  }

  @Override
  public CompletionStage<Result> apply(
      Function<Http.RequestHeader, CompletionStage<Result>> nextFilter,
      Http.RequestHeader requestHeader) {
    if (isPublicEndpoint(requestHeader.path()) || AuthController.isAuthenticated(requestHeader)) {
      return nextFilter.apply(requestHeader);
    }
    return CompletableFuture.completedFuture(
        Results.redirect(controllers.routes.AuthController.login()));
  }

  /** Returns true if the given request path is in the public allowlist. */
  static boolean isPublicEndpoint(String rawPath) {
    if (rawPath == null) {
      return false;
    }
    String path =
        rawPath.length() > 1 && rawPath.endsWith("/")
            ? rawPath.substring(0, rawPath.length() - 1)
            : rawPath;

    if (PUBLIC_PATHS.contains(path)) {
      return true;
    }
    for (String prefix : PUBLIC_PREFIXES) {
      if (rawPath.startsWith(prefix)) {
        return true;
      }
    }
    return PUBLIC_SANDBOX_PATTERN.matcher(path).matches();
  }
}
