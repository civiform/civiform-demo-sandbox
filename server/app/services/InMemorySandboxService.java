package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import models.SandboxStatus;

/**
 * In-memory {@link SandboxService} for tests and local development without Docker. Keeps state in a
 * {@link ConcurrentHashMap} — resets on server restart or via {@link #resetToSeedState()}.
 *
 * <p>Selected when {@code sandbox.runtime = "inmemory"} (used by the Playwright browser tests).
 */
@Singleton
public class InMemorySandboxService implements SandboxService {

  private static final Comparator<SandboxInstance> NEWEST_FIRST =
      Comparator.comparing(
              SandboxInstance::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(SandboxInstance::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final Clock clock;
  private final Map<String, SandboxInstance> sandboxes = new ConcurrentHashMap<>();

  @Inject
  public InMemorySandboxService(Clock clock) {
    this.clock = checkNotNull(clock);
    seed();
  }

  /** Restores the in-memory store to its initial seeded demo sandboxes. */
  public synchronized void resetToSeedState() {
    sandboxes.clear();
    seed();
  }

  private void seed() {
    Instant now = clock.instant();

    // 1. Seed one RUNNING demo sandbox for UI development and browser tests.
    // ID matches SEEDED_SANDBOX_ID in browser-test/src/support/config.ts.
    String demoId = "sb-demo0001";
    sandboxes.put(
        demoId,
        SandboxInstance.builder()
            .id(demoId)
            .cityName("Burlington, VT")
            .subdomain("burlington-vt")
            .civiformVersion("v2.22.0")
            .status(SandboxStatus.RUNNING)
            .url("https://burlington-vt.sandbox.civiform.dev")
            .adminEmail("admin@civiform.dev")
            .pin("482917")
            .accessToken("demo-token-001")
            .hostPort(10000)
            .databaseName("sandbox_demo_sb_1")
            .createdAt(now.minus(Duration.ofHours(2)))
            .expiresAt(now.plus(Duration.ofDays(30)))
            .build());

    // 2. Seed one DELETED tombstone row (scrubbed PIN/token/email, matching deleteSandbox()).
    String deletedId = "sb-demo0002";
    sandboxes.put(
        deletedId,
        SandboxInstance.builder()
            .id(deletedId)
            .cityName("Springfield, IL")
            .subdomain("springfield-il")
            .civiformVersion("v2.22.0")
            .status(SandboxStatus.DELETED)
            .url("https://springfield-il.sandbox.civiform.dev")
            .adminEmail("")
            .pin("")
            .accessToken("")
            .hostPort(10001)
            .databaseName("sandbox_demo_sb_2")
            .createdAt(now.minus(Duration.ofDays(45)))
            .expiresAt(now.minus(Duration.ofDays(15)))
            .deletedAt(now.minus(Duration.ofDays(8)))
            .build());
  }

  @Override
  public CompletionStage<ImmutableList<SandboxInstance>> listSandboxes() {
    ImmutableList<SandboxInstance> sorted =
        sandboxes.values().stream()
            .sorted(NEWEST_FIRST)
            .collect(ImmutableList.toImmutableList());
    return CompletableFuture.completedFuture(sorted);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> getSandbox(String id) {
    return CompletableFuture.completedFuture(Optional.ofNullable(sandboxes.get(id)));
  }

  @Override
  public CompletionStage<SandboxInstance> createSandbox(CreateSandboxRequest request) {
    Instant now = clock.instant();
    String id = "sb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String subdomain = request.getSubdomain() != null ? request.getSubdomain() : "demo";
    SandboxInstance instance = SandboxInstance.builder()
        .id(id)
        .cityName(request.getCityName())
        .subdomain(subdomain)
        .civiformVersion("latest")
        .status(SandboxStatus.PROVISIONING) // stays PROVISIONING — real impl updates async
        .url("https://" + subdomain + ".sandbox.civiform.dev")
        .adminEmail(request.getAdminEmail() != null ? request.getAdminEmail() : "")
        .pin(request.getPin() != null ? request.getPin() : "000000")
        .accessToken(UUID.randomUUID().toString())
        .googleAnalyticsId(request.getGoogleAnalyticsId())
        .googleAnalyticsUrl(request.getGoogleAnalyticsUrl())
        .hostPort(10001)
        .databaseName("sandbox_" + id.replace("-", "_"))
        .createdAt(now)
        .expiresAt(now.plus(Duration.ofDays(request.getExpirationDays())))
        .build();
    sandboxes.put(id, instance);
    return CompletableFuture.completedFuture(instance);
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null || existing.getStatus() == SandboxStatus.DELETED) {
      return CompletableFuture.completedFuture(false);
    }
    // Soft-delete: mark as DELETED with timestamp so the dashboard can show the tombstone row.
    // PIN, token, and admin email are cleared to match the scrubbed tombstone the JDBC path keeps.
    SandboxInstance deleted = existing.toBuilder()
        .status(SandboxStatus.DELETED)
        .deletedAt(clock.instant())
        .pin("")
        .accessToken("")
        .adminEmail("")
        .lastDestroyError(null)
        .build();
    sandboxes.put(id, deleted);
    return CompletableFuture.completedFuture(true);
  }

  @Override
  public CompletionStage<Boolean> destroyExpiredSandbox(String id, Instant expiredBefore) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null
        || (existing.getStatus() != SandboxStatus.RUNNING
            && existing.getStatus() != SandboxStatus.FAILED
            && existing.getStatus() != SandboxStatus.DELETE_FAILED)
        || existing.getExpiresAt() == null
        || !existing.getExpiresAt().isBefore(expiredBefore)) {
      return CompletableFuture.completedFuture(false);
    }
    return deleteSandbox(id);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null
        || existing.getStatus() == SandboxStatus.PROVISIONING
        || existing.getStatus() == SandboxStatus.DELETING
        || existing.getStatus() == SandboxStatus.DELETED) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    Instant now = clock.instant();
    Instant base = existing.getExpiresAt().isBefore(now) ? now : existing.getExpiresAt();
    SandboxInstance extended = existing.toBuilder()
        .expiresAt(base.plus(Duration.ofDays(days)))
        .build();
    sandboxes.put(id, extended);
    return reprovisionSandbox(id);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> reprovisionSandbox(String id) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null
        || existing.getStatus() == SandboxStatus.PROVISIONING
        || existing.getStatus() == SandboxStatus.DELETING
        || existing.getStatus() == SandboxStatus.DELETED) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    String accessToken = existing.getAccessToken();
    if (accessToken == null || accessToken.isBlank()) {
      accessToken = UUID.randomUUID().toString();
    }
    SandboxInstance reprovisioning = existing.toBuilder()
        .accessToken(accessToken)
        .status(SandboxStatus.PROVISIONING)
        .build();
    sandboxes.put(id, reprovisioning);
    return CompletableFuture.completedFuture(Optional.of(reprovisioning));
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> updateSandbox(
      String id, UpdateSandboxRequest request) {
    SandboxInstance existing = sandboxes.get(id);
    if (existing == null
        || existing.getStatus() == SandboxStatus.PROVISIONING
        || existing.getStatus() == SandboxStatus.DELETING
        || existing.getStatus() == SandboxStatus.DELETED) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    SandboxInstance updated = existing.withUpdatedSettings(request);
    sandboxes.put(id, updated);
    return reprovisionSandbox(id);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin) {
    return CompletableFuture.completedFuture(
        Optional.ofNullable(sandboxes.get(sandboxId)).filter(sandbox -> {
          if (sandbox.getStatus() == SandboxStatus.DELETED
              || sandbox.getStatus() == SandboxStatus.DELETING) {
            return false;
          }
          // Constant-time comparison to prevent timing attacks
          return MessageDigest.isEqual(sandbox.getPin().getBytes(), pin.getBytes());
        }));
  }
}
