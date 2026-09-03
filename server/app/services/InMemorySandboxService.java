package services;

import com.google.common.collect.ImmutableList;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;
import models.SandboxInstance;
import models.SandboxStatus;

/**
 * In-memory {@link SandboxService} for tests and local development without Docker.
 * Keeps state in a ConcurrentHashMap — resets on server restart.
 *
 * <p>Sprint 1: replaced in production by {@link DockerSandboxService} via Guice binding.
 */
@Singleton
public class InMemorySandboxService implements SandboxService {

  private final Map<String, SandboxInstance> sandboxes = new ConcurrentHashMap<>();

  public InMemorySandboxService() {
    // Seed one demo sandbox for UI development
    String demoId = "demo-sb-1";
    sandboxes.put(
        demoId,
        SandboxInstance.builder()
            .id(demoId)
            .cityName("Burlington, VT")
            .civiformVersion("v2.22.0")
            .status(SandboxStatus.RUNNING)
            .url("https://demo.sandbox.civiform.dev")
            .adminEmail("admin@civiform.dev")
            .notes("Default demo sandbox — seeded on startup")
            .pin("482917")
            .hostPort(10000)
            .schemaName("sandbox_demo_sb_1")
            .createdAt(Instant.now().minus(Duration.ofHours(2)))
            .expiresAt(Instant.now().plus(Duration.ofDays(30)))
            .build());
  }

  @Override
  public CompletionStage<ImmutableList<SandboxInstance>> listSandboxes() {
    return CompletableFuture.completedFuture(ImmutableList.copyOf(sandboxes.values()));
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> getSandbox(String id) {
    return CompletableFuture.completedFuture(Optional.ofNullable(sandboxes.get(id)));
  }

  @Override
  public CompletionStage<SandboxInstance> createSandbox(
      String cityName, String version, String adminEmail, String notes) {
    String id = "sb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    SandboxInstance instance = SandboxInstance.builder()
        .id(id)
        .cityName(cityName)
        .civiformVersion(version != null && !version.isBlank() ? version : "latest")
        .status(SandboxStatus.PROVISIONING) // stays PROVISIONING — real impl updates async
        .url("http://localhost:10001")
        .adminEmail(adminEmail != null ? adminEmail : "")
        .notes(notes != null ? notes : "")
        .pin(String.format("%06d", (int) (Math.random() * 1_000_000)))
        .hostPort(10001)
        .schemaName("sandbox_" + id.replace("-", "_"))
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)))
        .build();
    sandboxes.put(id, instance);
    return CompletableFuture.completedFuture(instance);
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    return CompletableFuture.completedFuture(sandboxes.remove(id) != null);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    SandboxInstance extended = existing.toBuilder()
        .expiresAt(existing.getExpiresAt().plus(Duration.ofDays(days)))
        .build();
    sandboxes.put(id, extended);
    return CompletableFuture.completedFuture(Optional.of(extended));
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin) {
    return CompletableFuture.completedFuture(
        Optional.ofNullable(sandboxes.get(sandboxId)).filter(sandbox -> {
          // Constant-time comparison
          return MessageDigest.isEqual(sandbox.getPin().getBytes(), pin.getBytes());
        }));
  }
}
