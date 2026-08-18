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

  @Test
  public void listSandboxes_returnsInitialDemoSandbox() throws ExecutionException, InterruptedException {
    var sandboxes = service.listSandboxes().toCompletableFuture().get();
    assertThat(sandboxes).isNotEmpty();
    assertThat(sandboxes.get(0).getName()).isEqualTo("Civiform Demo Staging");
    assertThat(sandboxes.get(0).getStatus()).isEqualTo(SandboxStatus.RUNNING);
  }

  @Test
  public void createSandbox_createsAndRetrievesInstance() throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        "Test Sandbox", "v2.22.0", "tester@civiform.dev", "Integration test instance"
    ).toCompletableFuture().get();

    assertThat(created.getId()).isNotNull();
    assertThat(created.getName()).isEqualTo("Test Sandbox");
    assertThat(created.getCiviformVersion()).isEqualTo("v2.22.0");

    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getName()).isEqualTo("Test Sandbox");
  }

  @Test
  public void deleteSandbox_removesInstance() throws ExecutionException, InterruptedException {
    SandboxInstance created = service.createSandbox(
        "To Delete", "latest", "admin@civiform.dev", ""
    ).toCompletableFuture().get();

    Boolean deleted = service.deleteSandbox(created.getId()).toCompletableFuture().get();
    assertThat(deleted).isTrue();

    var retrieved = service.getSandbox(created.getId()).toCompletableFuture().get();
    assertThat(retrieved).isEmpty();
  }
}
