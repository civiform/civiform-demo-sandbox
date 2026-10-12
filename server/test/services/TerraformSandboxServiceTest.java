package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TerraformSandboxService}.
 *
 * <p>Strategy: mock {@link SandboxRepository} and stub {@link TerraformCli} via the protected
 * constructor, so no Terraform binary, AWS credentials, or Postgres instance is required.
 *
 * <p>Note that anything past the database-creation step of {@code createSandbox} cannot be covered
 * here — provisioning opens a real JDBC connection to the shared RDS as its first action. Those
 * paths belong to the integration test in Phase 5. What is covered is everything that happens
 * <em>before</em> the async work, which is where the invariants that matter to the sales rep live:
 * status, PIN, and priority allocation.
 */
public class TerraformSandboxServiceTest {

  private SandboxRepository repository;
  private TerraformCli terraform;
  private Config config;
  private Path platformOutputsFile;
  private Path moduleDir;

  /** A minimal but complete set of platform outputs, in `terraform output -json` shape. */
  private static final String PLATFORM_OUTPUTS =
      """
      {
        "base_domain":                 {"value": "sandbox.civiform.dev"},
        "rds_endpoint":                {"value": "rds.internal"},
        "vpc_id":                      {"value": "vpc-123"},
        "private_subnet_ids":          {"value": ["subnet-a", "subnet-b"]},
        "alb_security_group_id":       {"value": "sg-alb"},
        "alb_https_listener_arn":      {"value": "arn:listener"},
        "ecs_cluster_arn":             {"value": "arn:cluster"},
        "ecs_cluster_name":            {"value": "civiform-sandbox-cluster"},
        "cloudwatch_log_group":        {"value": "/ecs/civiform-sandbox"},
        "sandbox_secrets_kms_key_arn": {"value": "arn:kms"},
        "placeholder_secrets":         {"value": [
          {"name": "ADFS_SECRET", "value_arn": "arn:s1", "secret_arn": "arn:s1"}
        ]}
      }
      """;

  @Before
  public void setUp() throws IOException {
    repository = mock(SandboxRepository.class);
    terraform = mock(TerraformCli.class);

    platformOutputsFile = Files.createTempFile("platform-outputs", ".json");
    Files.writeString(platformOutputsFile, PLATFORM_OUTPUTS, StandardCharsets.UTF_8);

    moduleDir = Files.createTempDirectory("tf-module");
    Files.writeString(moduleDir.resolve("main.tf"), "# stub\n", StandardCharsets.UTF_8);

    config = config(platformOutputsFile);

    when(repository.nextListenerPriority()).thenReturn(1000);
  }

  private Config config(Path outputsFile) {
    Map<String, Object> values = new HashMap<>();
    values.put("sandbox.terraform.binary", "terraform");
    values.put("sandbox.terraform.moduleDir", moduleDir.toString());
    values.put("sandbox.terraform.stateBucket", "civiform-sandbox-tfstate");
    values.put("sandbox.terraform.platformOutputsFile", outputsFile.toString());
    values.put("sandbox.terraform.civiformImageTag", "latest");
    values.put("sandbox.terraform.timeoutMinutes", 45);
    values.put("sandbox.aws.region", "us-east-1");
    values.put("sandbox.rds.adminDatabase", "postgres");
    values.put("sandbox.rds.adminUser", "sandbox_master");
    values.put("sandbox.rds.adminPassword", "secret");
    // Production defaults: empty host means "fall back to rds_endpoint".
    values.put("sandbox.rds.adminHost", "");
    values.put("sandbox.rds.adminPort", 5432);
    return ConfigFactory.parseMap(values);
  }

  /** Variant of {@link #config(Path)} with the admin connection pointed at a tunnel. */
  private Config configWithAdminOverride(String host, int port) {
    Map<String, Object> values = new HashMap<>();
    config(platformOutputsFile)
        .entrySet()
        .forEach(e -> values.put(e.getKey(), e.getValue().unwrapped()));
    values.put("sandbox.rds.adminHost", host);
    values.put("sandbox.rds.adminPort", port);
    return ConfigFactory.parseMap(values);
  }

  private TerraformSandboxService newService() {
    return new TerraformSandboxService(repository, config, terraform);
  }

  private CreateSandboxRequest request() {
    return CreateSandboxRequest.builder()
        .cityName("Burlington, VT")
        .subdomain("burlington-vt")
        .pin("123456")
        .adminEmail("bd-lead@exygy.com")
        .expirationDays(30)
        .build();
  }

  // ── createSandbox ───────────────────────────────────────────────────────────

  @Test
  public void createSandbox_returnsProvisioningNotRunning() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    // The sales rep must never be shown a URL that is not serving yet. Terraform
    // apply takes minutes; anything other than PROVISIONING here is a lie.
    assertThat(created.getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
  }

  @Test
  public void createSandbox_persistsBeforeReturning() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    ArgumentCaptor<SandboxInstance> saved = ArgumentCaptor.forClass(SandboxInstance.class);
    verify(repository).save(saved.capture());

    // Persisted synchronously, so a status poll issued immediately after the
    // redirect finds the row rather than a 404.
    assertThat(saved.getValue().getId()).isEqualTo(created.getId());
    assertThat(saved.getValue().getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
  }

  @Test
  public void createSandbox_usesRepSuppliedPinAndSubdomain() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    assertThat(created.getPin()).isEqualTo("123456");
    assertThat(created.getSubdomain()).isEqualTo("burlington-vt");
  }

  @Test
  public void createSandbox_urlUsesPlatformBaseDomain() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    // Built from the platform output, not a hardcoded literal — the wildcard
    // certificate and the Cloudflare record are both scoped to that domain.
    assertThat(created.getUrl()).isEqualTo("https://burlington-vt.sandbox.civiform.dev");
  }

  @Test
  public void createSandbox_allocatesListenerPriorityFromSequence() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    verify(repository).nextListenerPriority();
    assertThat(created.getListenerPriority()).isEqualTo(1000);
  }

  @Test
  public void createSandbox_concurrentCallsGetDistinctPriorities() throws Exception {
    AtomicInteger seq = new AtomicInteger(1000);
    when(repository.nextListenerPriority()).thenAnswer(i -> seq.getAndIncrement());

    TerraformSandboxService service = newService();

    List<Integer> allocated = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      allocated.add(
          service.createSandbox(request()).toCompletableFuture().get().getListenerPriority());
    }

    // ALB rejects duplicate priorities on a listener. A collision would fail the
    // apply several minutes in, after the ECS service already exists.
    assertThat(allocated).doesNotHaveDuplicates();
  }

  @Test
  public void createSandbox_databaseNameHasNoHyphens() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    // The id is "sb-<hex>", but the same string becomes a Postgres database and
    // role name. Unquoted identifiers cannot contain hyphens.
    assertThat(created.getDatabaseName()).doesNotContain("-");
    assertThat(created.getId()).contains("-");
  }

  @Test
  public void createSandbox_expiresAfterRequestedDays() throws Exception {
    SandboxInstance created = newService().createSandbox(request()).toCompletableFuture().get();

    long days = ChronoUnit.DAYS.between(created.getCreatedAt(), created.getExpiresAt());
    assertThat(days).isEqualTo(30);
  }

  // ── validatePin ─────────────────────────────────────────────────────────────

  private SandboxInstance live(String pin) {
    return SandboxInstance.builder()
        .id("sb-abcd1234")
        .pin(pin)
        .status(SandboxStatus.RUNNING)
        .expiresAt(Instant.now().plus(10, ChronoUnit.DAYS))
        .build();
  }

  @Test
  public void validatePin_correctPinReturnsSandbox() throws Exception {
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(live("123456")));

    assertThat(newService().validatePin("sb-abcd1234", "123456").toCompletableFuture().get())
        .isPresent();
  }

  @Test
  public void validatePin_wrongPinReturnsEmpty() throws Exception {
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(live("123456")));

    assertThat(newService().validatePin("sb-abcd1234", "000000").toCompletableFuture().get())
        .isEmpty();
  }

  @Test
  public void validatePin_prefixOfCorrectPinIsRejected() throws Exception {
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(live("123456")));

    // Guards against a length-insensitive comparison.
    assertThat(newService().validatePin("sb-abcd1234", "12345").toCompletableFuture().get())
        .isEmpty();
  }

  @Test
  public void validatePin_unknownSandboxReturnsEmpty() throws Exception {
    when(repository.findById("sb-nope")).thenReturn(Optional.empty());

    assertThat(newService().validatePin("sb-nope", "123456").toCompletableFuture().get()).isEmpty();
  }

  @Test
  public void validatePin_expiredSandboxRejectedEvenWithCorrectPin() throws Exception {
    SandboxInstance expired =
        live("123456").toBuilder().expiresAt(Instant.now().minus(1, ChronoUnit.DAYS)).build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(expired));

    // A 30-day trial that keeps working on day 31 is a security finding, not a
    // courtesy. The PIN being correct is irrelevant.
    assertThat(newService().validatePin("sb-abcd1234", "123456").toCompletableFuture().get())
        .isEmpty();
  }

  @Test
  public void validatePin_deletedSandboxRejected() throws Exception {
    SandboxInstance tombstone = live("123456").toBuilder().status(SandboxStatus.DELETED).build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(tombstone));

    assertThat(newService().validatePin("sb-abcd1234", "123456").toCompletableFuture().get())
        .isEmpty();
  }

  @Test
  public void validatePin_deletingSandboxRejected() throws Exception {
    SandboxInstance deleting = live("123456").toBuilder().status(SandboxStatus.DELETING).build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(deleting));

    assertThat(newService().validatePin("sb-abcd1234", "123456").toCompletableFuture().get())
        .isEmpty();
  }

  // ── deleteSandbox ───────────────────────────────────────────────────────────

  @Test
  public void deleteSandbox_unknownIdReturnsFalse() throws Exception {
    when(repository.findById("sb-nope")).thenReturn(Optional.empty());

    assertThat(newService().deleteSandbox("sb-nope").toCompletableFuture().get()).isFalse();
    verify(terraform, never()).destroy(any(), any(), any());
  }

  @Test
  public void deleteSandbox_marksDeletingSynchronouslyBeforeReturning() throws Exception {
    SandboxInstance running = live("123456").toBuilder().databaseName("sb_abcd1234").build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(running));

    // Returns immediately once DELETING is persisted, matching createSandbox —
    // teardown runs in the background so the HTTP redirect does not block for ~3m.
    assertThat(newService().deleteSandbox("sb-abcd1234").toCompletableFuture().get()).isTrue();
    verify(repository).updateStatus("sb-abcd1234", SandboxStatus.DELETING);
  }

  @Test
  public void deleteSandbox_deletingSandboxIsNotTornDownAgain() throws Exception {
    SandboxInstance deleting =
        live("123456").toBuilder()
            .status(SandboxStatus.DELETING)
            .databaseName("sb_abcd1234")
            .build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(deleting));

    // A second delete while the first is still running would launch a competing
    // `terraform destroy`, collide on the S3 .tflock (HTTP 412 PreconditionFailed),
    // and flip the row to DELETE_FAILED while the first destroy is succeeding.
    assertThat(newService().deleteSandbox("sb-abcd1234").toCompletableFuture().get()).isFalse();
    verify(terraform, never()).destroy(any(), any(), any());
    verify(repository, never()).updateStatus(anyString(), any());
  }

  @Test
  public void deleteSandbox_tombstoneIsNotTornDownAgain() throws Exception {
    SandboxInstance tombstone =
        live("").toBuilder().status(SandboxStatus.DELETED).databaseName("sb_abcd1234").build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(tombstone));

    assertThat(newService().deleteSandbox("sb-abcd1234").toCompletableFuture().get()).isFalse();

    // Re-running teardown on a tombstone would fail the DROP DATABASE and flip it
    // to DELETE_FAILED, falsely signalling that something is still running.
    verify(terraform, never()).destroy(any(), any(), any());
    verify(repository, never()).updateStatus(anyString(), eq(SandboxStatus.DELETE_FAILED));
  }

  @Test
  public void deleteSandbox_deleteFailedSandboxCanBeRetried() throws Exception {
    SandboxInstance deleteFailed =
        live("123456").toBuilder()
            .status(SandboxStatus.DELETE_FAILED)
            .databaseName("sb_abcd1234")
            .build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(deleteFailed));

    // After a restart flips an interrupted DELETING row to DELETE_FAILED, the
    // operator must be able to retry deleting it.
    assertThat(newService().deleteSandbox("sb-abcd1234").toCompletableFuture().get()).isTrue();
    verify(repository).updateStatus("sb-abcd1234", SandboxStatus.DELETING);
  }

  @Test
  public void deleteSandbox_failedProvisionSandboxCanBeDeleted() throws Exception {
    SandboxInstance failed =
        live("123456").toBuilder()
            .status(SandboxStatus.FAILED)
            .databaseName("sb_abcd1234")
            .build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(failed));

    // After a restart flips an interrupted PROVISIONING row to FAILED, the
    // operator must be able to tear down any partially-created resources.
    assertThat(newService().deleteSandbox("sb-abcd1234").toCompletableFuture().get()).isTrue();
    verify(repository).updateStatus("sb-abcd1234", SandboxStatus.DELETING);
  }

  @Test
  public void construction_failsInterruptedOperationsOnStartup() {
    newService();

    // Any PROVISIONING or DELETING rows left behind by a previous process are
    // transitioned to FAILED / DELETE_FAILED when the service starts up.
    verify(repository).failInterruptedOperations();
  }

  // ── extendSandbox ───────────────────────────────────────────────────────────

  @Test
  public void extendSandbox_updatesExpiryWithoutReinserting() throws Exception {
    Instant original = Instant.now().plus(5, ChronoUnit.DAYS);
    SandboxInstance existing = live("123456").toBuilder().expiresAt(original).build();
    when(repository.findById("sb-abcd1234")).thenReturn(Optional.of(existing));

    Optional<SandboxInstance> result =
        newService().extendSandbox("sb-abcd1234", 7).toCompletableFuture().get();

    assertThat(result).isPresent();
    assertThat(result.get().getExpiresAt()).isEqualTo(original.plus(7, ChronoUnit.DAYS));

    verify(repository).updateExpiry(eq("sb-abcd1234"), eq(original.plus(7, ChronoUnit.DAYS)));
    // save() is a plain INSERT; calling it here would raise a duplicate key violation.
    verify(repository, never()).save(any());
  }

  @Test
  public void extendSandbox_unknownIdReturnsEmpty() throws Exception {
    when(repository.findById("sb-nope")).thenReturn(Optional.empty());

    assertThat(newService().extendSandbox("sb-nope", 7).toCompletableFuture().get()).isEmpty();
  }

  // ── platform outputs ────────────────────────────────────────────────────────

  @Test
  public void construction_failsFastWhenPlatformOutputsFileMissing() {
    Config missing = config(Path.of("/nonexistent/platform-outputs.json"));

    // Better to refuse to start than to accept a sandbox creation and fail it
    // minutes later inside an apply.
    assertThatThrownBy(() -> new TerraformSandboxService(repository, missing, terraform))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terraform -chdir=terraform output -json");
  }

  @Test
  public void construction_failsWhenPlatformOutputsIncomplete() throws IOException {
    Path partial = Files.createTempFile("partial-outputs", ".json");
    Files.writeString(partial, "{\"vpc_id\": {\"value\": \"vpc-123\"}}", StandardCharsets.UTF_8);

    TerraformSandboxService service =
        new TerraformSandboxService(repository, config(partial), terraform);

    // base_domain is read while building the sandbox URL, before any async work,
    // so an incomplete outputs file surfaces at creation rather than mid-apply.
    assertThatThrownBy(() -> service.createSandbox(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base_domain");
  }

  // ── admin connection routing ────────────────────────────────────────────────

  @Test
  public void rdsUrl_defaultsToPlatformEndpoint() {
    // The production case: the builder runs in the VPC and talks to RDS directly.
    assertThat(newService().rdsUrl("postgres"))
        .isEqualTo("jdbc:postgresql://rds.internal:5432/postgres?ssl=true&sslmode=require");
  }

  @Test
  public void rdsUrl_honoursAdminHostAndPortOverride() {
    TerraformSandboxService service =
        new TerraformSandboxService(
            repository, configWithAdminOverride("host.docker.internal", 15432), terraform);

    // The local-development case: an SSM port-forward standing in for RDS.
    assertThat(service.rdsUrl("sb_a1b2c3d4"))
        .isEqualTo(
            "jdbc:postgresql://host.docker.internal:15432/sb_a1b2c3d4?ssl=true&sslmode=require");
  }

  @Test
  public void adminHostOverride_doesNotChangeTheSandboxesOwnDatabaseHost() throws Exception {
    TerraformSandboxService service =
        new TerraformSandboxService(
            repository, configWithAdminOverride("host.docker.internal", 15432), terraform);

    // The whole reason this override exists rather than editing rds_endpoint in
    // the platform outputs file. rds_endpoint is also passed to the sandbox stack
    // as db_address, so if the two were the same knob, tunnelling locally would
    // deploy an ECS task configured to reach a developer's laptop — and it would
    // fail its health check in AWS for reasons invisible from the tunnel.
    service.createSandbox(request()).toCompletableFuture().get();

    ArgumentCaptor<SandboxInstance> saved = ArgumentCaptor.forClass(SandboxInstance.class);
    verify(repository).save(saved.capture());

    assertThat(service.rdsUrl("postgres")).contains("host.docker.internal:15432");
    assertThat(saved.getValue().getUrl()).contains("sandbox.civiform.dev");
  }
}
