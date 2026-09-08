package services;

import com.google.common.collect.ImmutableList;
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
   * Extends a sandbox's expiry by {@code days} days from now.
   * Returns the updated instance, or empty if the sandbox was not found.
   */
  CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days);

  /**
   * Validates a PIN for a sandbox. Returns the sandbox if PIN matches, empty otherwise.
   * Must use constant-time comparison to prevent timing attacks.
   */
  CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin);
}

