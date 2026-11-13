package services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Before;
import org.junit.Test;

public class InMemorySandboxServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private InMemorySandboxService service;

  @Before
  public void setUp() {
    service = new InMemorySandboxService(FIXED_CLOCK);
  }

  private static CreateSandboxRequest makeRequest(String cityName, String subdomain, String pin) {
    return CreateSandboxRequest.builder()
        .cityName(cityName)
        .subdomain(subdomain)
        .pin(pin)
        .adminEmail("tester@civiform.dev")
        .expirationDays(30)
        .build();
  }

  @Test
  public void listSandboxes_returnsInitialSeededSandboxesSortedNewestFirst()
      throws ExecutionException, InterruptedException {
    var sandboxes = service.listSandboxes().toCompletableFuture().get();
    assertThat(sandboxes).hasSize(2);

    SandboxInstance running = sandboxes.get(0);
    assertThat(running.getId()).isEqualTo("sb-demo0001");
    assertThat(running.getCityName()).isEqualTo("Burlington, VT");
    assertThat(running.getStatus()).isEqualTo(SandboxStatus.RUNNING);
    assertThat(running.getCreatedAt()).isEqualTo(FIXED_NOW.minus(Duration.ofHours(2)));
    assertThat(running.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));

    SandboxInstance tombstone = sandboxes.get(1);
    assertThat(tombstone.getId()).isEqualTo("sb-demo0002");
    assertThat(tombstone.getCityName()).isEqualTo("Springfield, IL");
    assertThat(tombstone.getStatus()).isEqualTo(SandboxStatus.DELETED);
    assertThat(tombstone.getPin()).isEmpty();
    assertThat(tombstone.getAccessToken()).isEmpty();
    assertThat(tombstone.getAdminEmail()).isEmpty();
    assertThat(tombstone.getCreatedAt()).isEqualTo(FIXED_NOW.minus(Duration.ofDays(45)));
    assertThat(tombstone.getExpiresAt()).isEqualTo(FIXED_NOW.minus(Duration.ofDays(15)));
    assertThat(tombstone.getDeletedAt()).isEqualTo(FIXED_NOW.minus(Duration.ofDays(8)));
    assertThat(service.validatePin("sb-demo0002", "").toCompletableFuture().get()).isEmpty();
  }

  @Test
  public void createSandbox_createsAndRetrievesInstanceUsingInjectedClock()
      throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        makeRequest("Test Sandbox", "test-sandbox", "482917")
    ).toCompletableFuture().get();

    assertThat(created.getId()).isNotNull();
    assertThat(created.getCityName()).isEqualTo("Test Sandbox");
    assertThat(created.getSubdomain()).isEqualTo("test-sandbox");
    assertThat(created.getPin()).isEqualTo("482917");
    assertThat(created.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(created.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));

    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getCityName()).isEqualTo("Test Sandbox");

    // Newly created sandbox (createdAt = FIXED_NOW) sorts ahead of seeded rows.
    var all = service.listSandboxes().toCompletableFuture().get();
    assertThat(all.get(0).getId()).isEqualTo(created.getId());
  }

  @Test
  public void deleteSandbox_softDeletesInstanceWithClockTimestamp()
      throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        makeRequest("To Delete", "to-delete", "000000")
    ).toCompletableFuture().get();

    Boolean deleted = service.deleteSandbox(created.getId()).toCompletableFuture().get();
    assertThat(deleted).isTrue();

    // Soft-delete: sandbox still exists but has DELETED status and the clock's deletedAt timestamp
    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getStatus()).isEqualTo(SandboxStatus.DELETED);
    assertThat(retrieved.get().getDeletedAt()).isEqualTo(FIXED_NOW);

    // The tombstone is scrubbed: no PIN or admin email retained.
    assertThat(retrieved.get().getPin()).isEmpty();
    assertThat(retrieved.get().getAdminEmail()).isEmpty();

    // Deleting a tombstone again is a no-op.
    Boolean deletedAgain = service.deleteSandbox(created.getId()).toCompletableFuture().get();
    assertThat(deletedAgain).isFalse();
  }

  @Test
  public void resetToSeedState_removesCreatedSandboxesAndRestoresMutatedSeedRow()
      throws ExecutionException, InterruptedException {
    SandboxInstance created =
        service
            .createSandbox(makeRequest("Ephemeral City", "ephemeral-city", "123456"))
            .toCompletableFuture()
            .get();
    service.deleteSandbox("sb-demo0001").toCompletableFuture().get();

    service.resetToSeedState();

    assertThat(service.getSandbox(created.getId()).toCompletableFuture().get()).isEmpty();
    SandboxInstance restored =
        service.getSandbox("sb-demo0001").toCompletableFuture().get().orElseThrow();
    assertThat(restored.getStatus()).isEqualTo(SandboxStatus.RUNNING);
    assertThat(restored.getPin()).isEqualTo("482917");
    assertThat(service.listSandboxes().toCompletableFuture().get()).hasSize(2);
  }

  @Test
  public void deleteSandbox_returnsFalseForMissingId() throws ExecutionException, InterruptedException {
    Boolean deleted = service.deleteSandbox("does-not-exist").toCompletableFuture().get();
    assertThat(deleted).isFalse();
  }

  @Test
  public void reprovisionSandbox_transitionsRunningSandboxToProvisioning()
      throws ExecutionException, InterruptedException {
    var result = service.reprovisionSandbox("sb-demo0001").toCompletableFuture().get();
    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
  }

  @Test
  public void reprovisionSandbox_returnsEmptyForMissingOrProvisioningSandbox()
      throws ExecutionException, InterruptedException {
    assertThat(service.reprovisionSandbox("does-not-exist").toCompletableFuture().get()).isEmpty();

    SandboxInstance created =
        service
            .createSandbox(makeRequest("Already Provisioning", "prov-city", "111111"))
            .toCompletableFuture()
            .get();
    assertThat(service.reprovisionSandbox(created.getId()).toCompletableFuture().get()).isEmpty();
  }

  @Test
  public void extendSandbox_extendsExpiryAndTransitionsToProvisioning()
      throws ExecutionException, InterruptedException {
    var before = service.getSandbox("sb-demo0001").toCompletableFuture().get().orElseThrow();
    var result = service.extendSandbox("sb-demo0001", 15).toCompletableFuture().get();

    assertThat(result).isPresent();
    assertThat(result.get().getExpiresAt())
        .isEqualTo(before.getExpiresAt().plus(java.time.Duration.ofDays(15)));
    assertThat(result.get().getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
  }

  @Test
  public void updateSandbox_updatesSettingsAndTransitionsToProvisioning()
      throws ExecutionException, InterruptedException {
    java.time.Instant newExpiry = java.time.Instant.parse("2026-12-31T23:59:59Z");
    UpdateSandboxRequest req =
        UpdateSandboxRequest.builder()
            .cityName("Montpelier, VT")
            .googleAnalyticsId("G-VT12345")
            .expiresAt(newExpiry)
            .adminEmail("montpelier@civiform.dev")
            .civiformVersion("v2.24.0")
            .build();

    var result = service.updateSandbox("sb-demo0001", req).toCompletableFuture().get();

    assertThat(result).isPresent();
    SandboxInstance updated = result.get();
    assertThat(updated.getCityName()).isEqualTo("Montpelier, VT");
    assertThat(updated.getGoogleAnalyticsId()).isEqualTo("G-VT12345");
    assertThat(updated.getExpiresAt()).isEqualTo(newExpiry);
    assertThat(updated.getAdminEmail()).isEqualTo("montpelier@civiform.dev");
    assertThat(updated.getCiviformVersion()).isEqualTo("v2.24.0");
    assertThat(updated.getStatus()).isEqualTo(SandboxStatus.PROVISIONING);

    // Calling updateSandbox again while PROVISIONING returns empty
    assertThat(service.updateSandbox("sb-demo0001", req).toCompletableFuture().get()).isEmpty();
  }

  @Test
  public void destroyExpiredSandbox_skipsNonRunningOrUnexpiredAndMarksEligibleDeleted()
      throws ExecutionException, InterruptedException {
    // PROVISIONING sandbox is skipped even if cutoff is in the future
    SandboxInstance provisioning =
        service
            .createSandbox(makeRequest("Provisioning City", "prov-city", "654321"))
            .toCompletableFuture()
            .get();
    assertThat(
            service
                .destroyExpiredSandbox(
                    provisioning.getId(),
                    provisioning.getExpiresAt().plus(java.time.Duration.ofDays(1)))
                .toCompletableFuture()
                .get())
        .isFalse();
    assertThat(
            service.getSandbox(provisioning.getId()).toCompletableFuture().get().orElseThrow()
                .getStatus())
        .isEqualTo(SandboxStatus.PROVISIONING);

    // Seeded RUNNING sandbox skipped when cutoff is at/before its expiresAt
    SandboxInstance demo = service.getSandbox("sb-demo0001").toCompletableFuture().get().orElseThrow();
    assertThat(
            service
                .destroyExpiredSandbox("sb-demo0001", demo.getExpiresAt())
                .toCompletableFuture()
                .get())
        .isFalse();

    // Seeded RUNNING sandbox destroyed when its expiresAt is strictly before cutoff
    assertThat(
            service
                .destroyExpiredSandbox(
                    "sb-demo0001", demo.getExpiresAt().plusSeconds(1))
                .toCompletableFuture()
                .get())
        .isTrue();

    SandboxInstance deleted =
        service.getSandbox("sb-demo0001").toCompletableFuture().get().orElseThrow();
    assertThat(deleted.getStatus()).isEqualTo(SandboxStatus.DELETED);
    assertThat(deleted.getPin()).isEmpty();
    assertThat(deleted.getAccessToken()).isEmpty();
    assertThat(deleted.getAdminEmail()).isEmpty();
    assertThat(deleted.getDeletedAt()).isNotNull();

    // Deleted sandboxes reject PIN access, extension, re-provisioning, and duplicate deletion.
    assertThat(service.validatePin("sb-demo0001", "482917").toCompletableFuture().get()).isEmpty();
    assertThat(service.extendSandbox("sb-demo0001", 7).toCompletableFuture().get()).isEmpty();
    assertThat(service.reprovisionSandbox("sb-demo0001").toCompletableFuture().get()).isEmpty();
    assertThat(service.deleteSandbox("sb-demo0001").toCompletableFuture().get()).isFalse();
  }
}

