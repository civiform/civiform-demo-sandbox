package services;

import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import models.SandboxInstance;

public interface SandboxService {
  CompletionStage<ImmutableList<SandboxInstance>> listSandboxes();

  CompletionStage<Optional<SandboxInstance>> getSandbox(String id);

  /**
   * Creates a new sandbox from the given request. Returns immediately with a PROVISIONING instance.
   * Subdomain and PIN are provided by the sales rep (no auto-generation).
   */
  CompletionStage<SandboxInstance> createSandbox(CreateSandboxRequest request);

  CompletionStage<Boolean> deleteSandbox(String id);

  /**
   * Synchronously (as an awaitable stage) destroys an expired sandbox's infrastructure and
   * per-sandbox database, then marks its database record {@link models.SandboxStatus#DELETED}.
   *
   * <p>Re-reads the row at execution time and skips destruction (completing with {@code false})
   * unless the sandbox is still {@link models.SandboxStatus#RUNNING}, {@link
   * models.SandboxStatus#FAILED}, or {@link models.SandboxStatus#DELETE_FAILED} and its {@code
   * expiresAt} is strictly before {@code expiredBefore}. Completes with {@code true} when
   * destroyed, or exceptionally if teardown fails so scheduled jobs can track retry counts and
   * alert SRE.
   */
  CompletionStage<Boolean> destroyExpiredSandbox(String id, Instant expiredBefore);

  /**
   * Extends a sandbox's expiry by {@code days} days and triggers re-provisioning so the running
   * instance picks up the updated expiration date. Returns the updated instance (with status
   * PROVISIONING), or empty if the sandbox was not found or cannot be extended in its current
   * state.
   */
  CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days);

  /**
   * Re-runs provisioning against an existing sandbox without dropping or recreating its database.
   * Used to upgrade existing sandboxes to the latest infrastructure configuration (e.g. PIN gating)
   * or redeploy tasks. Returns the updated instance (with status PROVISIONING), or empty if the
   * sandbox does not exist or cannot be re-provisioned in its current state.
   */
  CompletionStage<Optional<SandboxInstance>> reprovisionSandbox(String id);

  /**
   * Updates editable settings on an existing sandbox (city name, Google Analytics ID, expiration
   * date, admin email, and CiviForm version) and triggers re-provisioning so the running instance
   * picks up the new settings. Returns the updated instance (with status PROVISIONING), or empty if
   * the sandbox does not exist or cannot be updated in its current state.
   */
  CompletionStage<Optional<SandboxInstance>> updateSandbox(String id, UpdateSandboxRequest request);

  /**
   * Validates a PIN for a sandbox. Returns the sandbox if PIN matches, empty otherwise.
   * Must use constant-time comparison to prevent timing attacks.
   */
  CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin);
}

