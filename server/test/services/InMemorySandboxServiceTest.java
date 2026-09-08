package services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Before;
import org.junit.Test;

public class InMemorySandboxServiceTest {

  private InMemorySandboxService service;

  @Before
  public void setUp() {
    service = new InMemorySandboxService();
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
  public void listSandboxes_returnsInitialDemoSandbox() throws ExecutionException, InterruptedException {
    var sandboxes = service.listSandboxes().toCompletableFuture().get();
    assertThat(sandboxes).isNotEmpty();
    assertThat(sandboxes.get(0).getId()).isEqualTo("sb-demo0001");
    assertThat(sandboxes.get(0).getCityName()).isEqualTo("Burlington, VT");
    assertThat(sandboxes.get(0).getStatus()).isEqualTo(SandboxStatus.RUNNING);
  }

  @Test
  public void createSandbox_createsAndRetrievesInstance() throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        makeRequest("Test Sandbox", "test-sandbox", "482917")
    ).toCompletableFuture().get();

    assertThat(created.getId()).isNotNull();
    assertThat(created.getCityName()).isEqualTo("Test Sandbox");
    assertThat(created.getSubdomain()).isEqualTo("test-sandbox");
    assertThat(created.getPin()).isEqualTo("482917");

    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getCityName()).isEqualTo("Test Sandbox");
  }

  @Test
  public void deleteSandbox_softDeletesInstance() throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        makeRequest("To Delete", "to-delete", "000000")
    ).toCompletableFuture().get();

    Boolean deleted = service.deleteSandbox(created.getId()).toCompletableFuture().get();
    assertThat(deleted).isTrue();

    // Soft-delete: sandbox still exists but has DELETED status and a deletedAt timestamp
    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getStatus()).isEqualTo(SandboxStatus.DELETED);
    assertThat(retrieved.get().getDeletedAt()).isNotNull();
  }

  @Test
  public void deleteSandbox_returnsFalseForMissingId() throws ExecutionException, InterruptedException {
    Boolean deleted = service.deleteSandbox("does-not-exist").toCompletableFuture().get();
    assertThat(deleted).isFalse();
  }
}

