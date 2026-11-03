package services;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import play.Logger;
import play.Logger.ALogger;
import play.libs.ws.WSClient;
import play.libs.ws.WSCookieBuilder;
import play.libs.ws.WSResponse;

/**
 * Seeds a running CiviForm sandbox with demo programs.
 *
 * <p>Calls CiviForm's built-in {@code POST /dev/seedProgramsHeadless} endpoint, which creates
 * sample questions, program categories, and two programs:
 *
 * <ul>
 *   <li><b>Minimal Sample Program</b> - one screen with an optional name question
 *   <li><b>Comprehensive Sample Program</b> - one of every question type across multiple screens,
 *       including predicates, enumerators, and statuses
 * </ul>
 *
 * <p>This endpoint is available when {@code STAGING_DISABLE_DEMO_MODE_LOGINS=false}, which all
 * sandboxes set by default. It requires no authentication and has CSRF disabled ({@code +nocsrf}
 * in CiviForm's routes file).
 *
 * <p>The sandbox is behind the ALB PIN gate, so the request must include the {@code
 * sb_access_<id>} cookie to bypass the ALB redirect rule. Redirect following is disabled so that
 * a 302 to the PIN page is detected as a failure rather than silently succeeding on the PIN page's
 * 200.
 *
 * <p>Must be called only after the sandbox container is healthy (status = RUNNING from health
 * check perspective).
 */
@Singleton
public class SeedingEngine {

  private static final ALogger log = Logger.of(SeedingEngine.class);

  /** Max attempts to hit the seed endpoint. */
  private static final int MAX_SEED_ATTEMPTS = 3;
  private static final int SEED_RETRY_DELAY_MS = 5_000;
  /** Timeout for the seed request - program creation can take a few seconds. */
  private static final int SEED_REQUEST_TIMEOUT_SECONDS = 60;

  private final WSClient ws;
  private final SandboxRepository repository;

  private final Executor seedingPool = Executors.newCachedThreadPool();

  @Inject
  public SeedingEngine(WSClient ws, SandboxRepository repository) {
    this.ws = checkNotNull(ws);
    this.repository = checkNotNull(repository);
  }

  /**
   * Seeds the given sandbox with CiviForm's built-in sample programs.
   *
   * <p>Sets status to SEEDING at start, but does NOT set RUNNING on success or FAILED on failure.
   * The caller decides the final status.
   *
   * @param sandbox the sandbox instance (must have a reachable URL and be healthy)
   * @return completes with {@code true} if seeding succeeded, {@code false} otherwise
   */
  public CompletionStage<Boolean> seedSandbox(SandboxInstance sandbox) {
    return CompletableFuture.supplyAsync(
        () -> {
          String sandboxId = sandbox.getId();
          String baseUrl = sandbox.getUrl();
          String accessToken = sandbox.getAccessToken();
          log.info(
              "[{}] Starting program seeding via /dev/seedProgramsHeadless at {}",
              sandboxId,
              baseUrl);

          repository.updateStatus(sandboxId, models.SandboxStatus.SEEDING);

          String cookieName = accessCookieName(sandboxId);

          for (int attempt = 1; attempt <= MAX_SEED_ATTEMPTS; attempt++) {
            try {
              WSResponse response =
                  ws.url(baseUrl + "/dev/seedProgramsHeadless")
                      .setRequestTimeout(
                          java.time.Duration.ofSeconds(SEED_REQUEST_TIMEOUT_SECONDS))
                      .setFollowRedirects(false)
                      .addCookie(
                          new WSCookieBuilder()
                              .setName(cookieName)
                              .setValue(accessToken)
                              .setPath("/")
                              .build())
                      .post("")
                      .toCompletableFuture()
                      .get();

              int status = response.getStatus();
              if (status == 200) {
                log.info("[{}] Seeding complete (attempt {})", sandboxId, attempt);
                return true;
              }

              // Any non-200 is a failure: 302 means the cookie was rejected (PIN gate),
              // 500 means seedProgramsInternal returned false
              log.warn(
                  "[{}] Seed attempt {} returned HTTP {}: {}",
                  sandboxId,
                  attempt,
                  status,
                  response
                      .getBody()
                      .substring(0, Math.min(500, response.getBody().length())));

            } catch (Exception e) {
              log.warn("[{}] Seed attempt {} failed: {}", sandboxId, attempt, e.getMessage());
            }

            if (attempt < MAX_SEED_ATTEMPTS) {
              try {
                Thread.sleep(SEED_RETRY_DELAY_MS);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
              }
            }
          }

          log.error("[{}] Seeding failed after {} attempts", sandboxId, MAX_SEED_ATTEMPTS);
          return false;
        },
        seedingPool);
  }

  /**
   * Name of the ALB PIN-gate access cookie for a sandbox. Matches {@code
   * aws_lb_listener_rule.sandbox_allow} in {@code terraform/sandbox/main.tf} and {@code
   * SandboxController.accessCookieName}.
   */
  static String accessCookieName(String sandboxId) {
    return "sb_access_" + sandboxId.replace("-", "_");
  }
}
