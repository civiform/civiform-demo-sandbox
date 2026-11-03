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
import play.libs.ws.WSResponse;

/**
 * Seeds a running CiviForm sandbox with demo programs.
 *
 * <p>Calls CiviForm's built-in {@code POST /dev/seedProgramsHeadless} endpoint, which creates
 * sample questions, program categories, and two programs:
 *
 * <ul>
 *   <li><b>Minimal Sample Program</b> — one screen with an optional name question
 *   <li><b>Comprehensive Sample Program</b> — one of every question type across multiple screens,
 *       including predicates, enumerators, and statuses
 * </ul>
 *
 * <p>This endpoint is available when {@code STAGING_DISABLE_DEMO_MODE_LOGINS=false}, which all
 * sandboxes set by default. It requires no authentication and has CSRF disabled ({@code +nocsrf}
 * in CiviForm's routes file).
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
  /** Timeout for the seed request — program creation can take a few seconds. */
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
   * <p>Sets status to SEEDING at start, RUNNING on success. On failure, logs the error but does
   * NOT set FAILED — the caller decides the failure policy.
   *
   * @param sandbox the sandbox instance (must have a reachable URL and be healthy)
   * @return completes with {@code true} if seeding succeeded, {@code false} otherwise
   */
  public CompletionStage<Boolean> seedSandbox(SandboxInstance sandbox) {
    return CompletableFuture.supplyAsync(
        () -> {
          String sandboxId = sandbox.getId();
          String baseUrl = sandbox.getUrl();
          log.info(
              "[{}] Starting program seeding via /dev/seedProgramsHeadless at {}",
              sandboxId,
              baseUrl);

          repository.updateStatus(sandboxId, models.SandboxStatus.SEEDING);

          for (int attempt = 1; attempt <= MAX_SEED_ATTEMPTS; attempt++) {
            try {
              WSResponse response =
                  ws.url(baseUrl + "/dev/seedProgramsHeadless")
                      .setRequestTimeout(
                          java.time.Duration.ofSeconds(SEED_REQUEST_TIMEOUT_SECONDS))
                      .post("")
                      .toCompletableFuture()
                      .get();

              int status = response.getStatus();
              if (status == 200) {
                log.info("[{}] Seeding complete (attempt {})", sandboxId, attempt);
                return true;
              }

              // 500 means seedProgramsInternal returned false — programs may already exist
              // or there was a transient DB error
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
}
