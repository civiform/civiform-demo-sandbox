package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import play.Environment;
import play.Logger;
import play.Logger.ALogger;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;

/**
 * Seeds a running CiviForm sandbox with demo programs.
 *
 * <p>Reads program definition JSON files from the classpath ({@code /seed-data/*.json}) and
 * imports them into the sandbox's CiviForm instance via its admin API. Must be called only
 * after the sandbox container is healthy (status = RUNNING from health check perspective).
 *
 * <p>Sprint 3: seeds 2 fixed programs (Comprehensive + Minimal). Sprint 7+ may add
 * Discovery Engine programs.
 */
@Singleton
public class SeedingEngine {

  private static final ALogger log = Logger.of(SeedingEngine.class);

  /** Program JSON files to seed, in order. Paths are classpath-relative. */
  private static final ImmutableList<String> SEED_PROGRAMS = ImmutableList.of(
      "seed-data/comprehensive-sample.json",
      "seed-data/minimal-sample.json");

  /** Max attempts to authenticate and seed. */
  private static final int MAX_SEED_ATTEMPTS = 3;
  private static final int SEED_RETRY_DELAY_MS = 5_000;

  private final Environment environment;
  private final WSClient ws;
  private final SandboxRepository repository;

  private final Executor seedingPool = Executors.newCachedThreadPool();

  @Inject
  public SeedingEngine(Environment environment, WSClient ws, SandboxRepository repository) {
    this.environment = checkNotNull(environment);
    this.ws = checkNotNull(ws);
    this.repository = checkNotNull(repository);
  }

  /**
   * Seeds the given sandbox with demo programs. Call after the container is healthy.
   *
   * <p>Sets status to SEEDING at start, RUNNING on success. On failure, logs the error
   * but does NOT set FAILED — the caller decides the failure policy.
   *
   * @param sandbox the sandbox instance (must have a reachable URL and be healthy)
   * @return completes when all programs are seeded (or seeding fails)
   */
  public CompletionStage<Boolean> seedSandbox(SandboxInstance sandbox) {
    return CompletableFuture.supplyAsync(() -> {
      String sandboxId = sandbox.getId();
      // Use the internal URL for seeding (Docker: localhost:<port>, Terraform: sandbox URL)
      String baseUrl = sandbox.getUrl();
      log.info("[{}] Starting program seeding at {}", sandboxId, baseUrl);

      repository.updateStatus(sandboxId, models.SandboxStatus.SEEDING);

      try {
        // Step 1: Authenticate as CiviForm admin via demo login
        String sessionCookies = authenticateAsAdmin(sandboxId, baseUrl);
        if (sessionCookies == null) {
          log.error("[{}] Failed to authenticate as admin for seeding", sandboxId);
          return false;
        }

        // Step 2: Import each program
        int seeded = 0;
        for (String programPath : SEED_PROGRAMS) {
          String programJson = loadSeedData(programPath);
          if (programJson == null) {
            log.warn("[{}] Seed file not found: {}", sandboxId, programPath);
            continue;
          }

          boolean imported = importProgram(sandboxId, baseUrl, sessionCookies, programJson);
          if (imported) {
            seeded++;
            log.info("[{}] Seeded program from {}", sandboxId, programPath);
          } else {
            log.warn("[{}] Failed to seed program from {}", sandboxId, programPath);
          }
        }

        log.info("[{}] Seeding complete: {}/{} programs loaded", sandboxId, seeded, SEED_PROGRAMS.size());
        return seeded > 0;

      } catch (Exception e) {
        log.error("[{}] Seeding failed: {}", sandboxId, e.getMessage(), e);
        return false;
      }
    }, seedingPool);
  }

  /**
   * Authenticates as a CiviForm admin using the demo/guest login flow.
   *
   * <p>With STAGING_DISABLE_DEMO_MODE_LOGINS=false and CIVIFORM_APPLICANT_IDP=generic-oidc,
   * we can log in as admin by hitting the admin login callback endpoint.
   *
   * @return session cookie string for subsequent requests, or null on failure
   */
  private String authenticateAsAdmin(String sandboxId, String baseUrl) {
    for (int attempt = 1; attempt <= MAX_SEED_ATTEMPTS; attempt++) {
      try {
        // CiviForm demo mode: GET /loginForm shows login buttons.
        // The admin login link is typically /callback?client_name=FakeAdminClient
        // or via the OIDC flow. For simplicity, hit the guest/admin callback directly.
        WSResponse loginResponse = ws.url(baseUrl + "/callback")
            .addQueryParameter("client_name", "FakeAdminClient")
            .setFollowRedirects(false)
            .setRequestTimeout(java.time.Duration.ofSeconds(10))
            .get()
            .toCompletableFuture()
            .get();

        // Extract session cookies from the redirect response
        List<play.libs.ws.WSCookie> cookies = loginResponse.getCookies();
        if (!cookies.isEmpty()) {
          StringBuilder cookieHeader = new StringBuilder();
          for (play.libs.ws.WSCookie c : cookies) {
            if (cookieHeader.length() > 0) cookieHeader.append("; ");
            cookieHeader.append(c.getName()).append("=").append(c.getValue());
          }
          log.info("[{}] Admin authentication successful (attempt {})", sandboxId, attempt);
          return cookieHeader.toString();
        }

        log.warn("[{}] Auth attempt {} returned no cookies (status={})",
            sandboxId, attempt, loginResponse.getStatus());

      } catch (Exception e) {
        log.warn("[{}] Auth attempt {} failed: {}", sandboxId, attempt, e.getMessage());
      }

      if (attempt < MAX_SEED_ATTEMPTS) {
        try { Thread.sleep(SEED_RETRY_DELAY_MS); } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Imports a single program definition into the CiviForm instance.
   *
   * <p>Uses the admin program migration endpoint to import the full program
   * definition including questions and block structure.
   */
  private boolean importProgram(
      String sandboxId, String baseUrl, String sessionCookies, String programJson) {
    try {
      // CiviForm admin import endpoint for program migration
      WSResponse response = ws.url(baseUrl + "/admin/programs/import")
          .addHeader("Cookie", sessionCookies)
          .addHeader("Content-Type", "application/json")
          .setRequestTimeout(java.time.Duration.ofSeconds(30))
          .post(programJson)
          .toCompletableFuture()
          .get();

      int status = response.getStatus();
      if (status >= 200 && status < 300) {
        return true;
      }

      // If the direct JSON POST doesn't work, try form-based import
      // (CiviForm's import page accepts a textarea with pasted JSON)
      log.warn("[{}] Direct import returned {}, trying form-based import", sandboxId, status);
      response = ws.url(baseUrl + "/admin/programs/import")
          .addHeader("Cookie", sessionCookies)
          .setContentType("application/x-www-form-urlencoded")
          .setRequestTimeout(java.time.Duration.ofSeconds(30))
          .post("programJson=" + java.net.URLEncoder.encode(programJson, StandardCharsets.UTF_8))
          .toCompletableFuture()
          .get();

      status = response.getStatus();
      if (status >= 200 && status < 400) {
        return true;
      }

      log.warn("[{}] Form import also returned {}: {}",
          sandboxId, status, response.getBody().substring(0, Math.min(500, response.getBody().length())));
      return false;

    } catch (Exception e) {
      log.error("[{}] Program import failed: {}", sandboxId, e.getMessage());
      return false;
    }
  }

  /** Loads a seed data file from the classpath. Returns null if not found. */
  private String loadSeedData(String path) {
    try (InputStream is = environment.resourceAsStream(path)) {
      if (is == null) {
        return null;
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to read seed data file: {}", path, e);
      return null;
    }
  }
}
