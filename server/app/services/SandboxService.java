package services;

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import models.SandboxInstance;

public interface SandboxService {
  CompletionStage<ImmutableList<SandboxInstance>> listSandboxes();

  CompletionStage<Optional<SandboxInstance>> getSandbox(String id);

  /**
   * Creates a new sandbox for the given city. Returns immediately with a PROVISIONING instance.
   * PIN is generated and stored before async container launch begins.
   */
  CompletionStage<SandboxInstance> createSandbox(
      String cityName, String version, String adminEmail, String notes);

  CompletionStage<Boolean> deleteSandbox(String id);

  /**
   * Validates a PIN for a sandbox. Returns the sandbox if PIN matches, empty otherwise.
   * Must use constant-time comparison to prevent timing attacks.
   */
  CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin);
}

