package services;

import com.google.common.collect.ImmutableList;
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

@Singleton
public class InMemorySandboxService implements SandboxService {
  private final Map<String, SandboxInstance> sandboxes = new ConcurrentHashMap<>();

  public InMemorySandboxService() {
    // Seed initial demo sandbox instance for the shell
    String demoId = "demo-sb-1";
    sandboxes.put(
        demoId,
        SandboxInstance.builder()
            .id(demoId)
            .name("Civiform Demo Staging")
            .civiformVersion("v2.22.0")
            .status(SandboxStatus.RUNNING)
            .url("https://demo-sb-1.civiform.dev")
            .adminEmail("admin@civiform.dev")
            .notes("Default demo sandbox environment initialized on startup")
            .createdAt(Instant.now().minus(Duration.ofHours(2)))
            .expiresAt(Instant.now().plus(Duration.ofHours(22)))
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
      String name, String version, String adminEmail, String notes) {
    String id = "sb-" + UUID.randomUUID().toString().substring(0, 8);
    SandboxInstance instance =
        SandboxInstance.builder()
            .id(id)
            .name(name)
            .civiformVersion(version != null && !version.isBlank() ? version : "latest")
            .status(SandboxStatus.RUNNING)
            .url("https://" + id + ".civiform.dev")
            .adminEmail(adminEmail)
            .notes(notes)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofHours(24)))
            .build();
    sandboxes.put(id, instance);
    return CompletableFuture.completedFuture(instance);
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    return CompletableFuture.completedFuture(sandboxes.remove(id) != null);
  }
}
