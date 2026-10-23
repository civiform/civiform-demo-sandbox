package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.typesafe.config.Config;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import models.SandboxStatus;
import org.apache.commons.lang3.RandomStringUtils;
import play.Logger;
import play.Logger.ALogger;

/**
 * {@link SandboxService} implementation that provisions sandboxes by running Terraform against the
 * {@code terraform/sandbox} root module.
 *
 * <p>Each sandbox is one Terraform state file and one {@code apply}. The shared platform — VPC,
 * RDS, ALB, ECS cluster, KMS key — is provisioned separately and arrives here as a JSON file of
 * platform outputs.
 *
 * <p>This deliberately does <em>not</em> shell out to cloud-deploy-infra's {@code
 * cloud/shared/bin/run setup}. That script prompts interactively for seven values, manages its own
 * Python virtualenv, mutates its own checkout, and provisions an entire standalone deployment
 * including a VPC, RDS instance and load balancer per sandbox. Consuming the two Terraform modules
 * directly gets the same production-parity task definition without any of that.
 */
@Singleton
public class TerraformSandboxService implements SandboxService {

  private static final ALogger log = Logger.of(TerraformSandboxService.class);

  private static final Duration HTTP_PROBE_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration DEFAULT_READINESS_POLL_INTERVAL = Duration.ofSeconds(10);

  /**
   * Passed as {@code db_password} when destroying.
   *
   * <p>The real password is not persisted — it exists only in Secrets Manager and in the sandbox's
   * Terraform state. Terraform requires a value for every declared variable even on destroy, but
   * the value is inert there: resources are destroyed from state, and {@code db_password} feeds
   * only the contents of a secret version that is itself being deleted. It participates in no
   * resource name, address, or identity.
   */
  private static final String DESTROY_PLACEHOLDER_PASSWORD = "unused-during-destroy";

  private final SandboxRepository repository;
  private final TerraformCli terraform;
  private final Config config;

  private final Path moduleDir;
  private final String stateBucket;
  private final String awsRegion;
  private final String civiformImageTag;
  private final Duration readinessTimeout;
  private final Duration readinessPollInterval;

  private final Map<String, JsonNode> platformOutputs;

  /** Dedicated pool. Terraform runs are long and blocking; Play's pool must not be used. */
  private final Executor provisioningPool = Executors.newCachedThreadPool();

  /**
   * Per-sandbox tail of in-flight background work. Different sandboxes run in parallel, but two
   * operations for the <em>same</em> sandbox share one S3 state lock and must run sequentially.
   */
  private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight =
      new ConcurrentHashMap<>();

  @Inject
  public TerraformSandboxService(SandboxRepository repository, Config config) {
    this(
        repository,
        config,
        new TerraformCli(
            config.getString("sandbox.terraform.binary"),
            Duration.ofMinutes(config.getInt("sandbox.terraform.timeoutMinutes"))));
  }

  /** Test seam: accepts a stubbed {@link TerraformCli} so no Terraform binary is required. */
  protected TerraformSandboxService(
      SandboxRepository repository, Config config, TerraformCli terraform) {
    this.repository = checkNotNull(repository);
    this.config = checkNotNull(config);
    this.terraform = checkNotNull(terraform);

    this.moduleDir = Path.of(config.getString("sandbox.terraform.moduleDir"));
    this.stateBucket = config.getString("sandbox.terraform.stateBucket");
    this.awsRegion = config.getString("sandbox.aws.region");
    this.civiformImageTag = config.getString("sandbox.terraform.civiformImageTag");
    this.readinessTimeout =
        config.hasPath("sandbox.terraform.readinessTimeoutMinutes")
            ? Duration.ofMinutes(config.getInt("sandbox.terraform.readinessTimeoutMinutes"))
            : DEFAULT_READINESS_TIMEOUT;
    this.readinessPollInterval =
        config.hasPath("sandbox.terraform.readinessPollIntervalSeconds")
            ? Duration.ofSeconds(config.getInt("sandbox.terraform.readinessPollIntervalSeconds"))
            : DEFAULT_READINESS_POLL_INTERVAL;

    this.platformOutputs = loadPlatformOutputs(config);

    this.repository.ensureSchemaInitialized();
    int recovered = this.repository.failInterruptedOperations();
    if (recovered > 0) {
      log.warn("Marked {} interrupted sandbox operation(s) as failed after restart", recovered);
    }
  }

  @Override
  public CompletionStage<ImmutableList<SandboxInstance>> listSandboxes() {
    return CompletableFuture.supplyAsync(repository::findAll, provisioningPool);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> getSandbox(String id) {
    return CompletableFuture.supplyAsync(() -> repository.findById(id), provisioningPool);
  }

  @Override
  public CompletionStage<SandboxInstance> createSandbox(CreateSandboxRequest request) {
    String id = "sb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    // Postgres identifiers cannot contain hyphens unless quoted everywhere, and the
    // same string is reused as the role name.
    String databaseName = id.replace("-", "_");
    String dbUser = databaseName;
    String dbPassword = generateSecret(32);

    // Allocated before anything is created, so the row records it even if apply fails
    // and the resources have to be reconciled by hand.
    int listenerPriority = repository.nextListenerPriority();

    String baseDomain = platformOutput("base_domain").asText();
    String url = "https://" + request.getSubdomain() + "." + baseDomain;

    SandboxInstance instance =
        SandboxInstance.builder()
            .id(id)
            .cityName(request.getCityName())
            .subdomain(request.getSubdomain())
            .civiformVersion(civiformImageTag)
            .status(SandboxStatus.PROVISIONING)
            .url(url)
            .adminEmail(request.getAdminEmail() != null ? request.getAdminEmail() : "")
            .pin(request.getPin())
            .accessToken(UUID.randomUUID().toString())
            .googleAnalyticsId(request.getGoogleAnalyticsId())
            .googleAnalyticsUrl(request.getGoogleAnalyticsUrl())
            .databaseName(databaseName)
            .listenerPriority(listenerPriority)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofDays(request.getExpirationDays())))
            .build();

    // Persisted before any async work so the PIN and URL are shown to the sales rep
    // immediately, while provisioning continues in the background.
    repository.save(instance);

    enqueueForSandbox(id, () -> provision(instance, dbUser, dbPassword));

    return CompletableFuture.completedFuture(instance);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> reprovisionSandbox(String id) {
    Optional<SandboxInstance> maybeSandbox = repository.findById(id);
    if (maybeSandbox.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    SandboxInstance existing = maybeSandbox.get();

    // Do not start a competing apply if the sandbox is already being provisioned,
    // torn down, or soft-deleted.
    if (existing.getStatus() == SandboxStatus.PROVISIONING
        || existing.getStatus() == SandboxStatus.DELETING
        || existing.getStatus() == SandboxStatus.DELETED) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    // Backfill access_token or listener_priority for legacy sandboxes created before
    // PIN gating or sequence-based priorities existed.
    String accessToken = existing.getAccessToken();
    boolean needsMetadataUpdate = false;
    if (accessToken == null || accessToken.isBlank()) {
      accessToken = UUID.randomUUID().toString();
      needsMetadataUpdate = true;
    }
    Integer listenerPriority = existing.getListenerPriority();
    if (listenerPriority == null) {
      listenerPriority = repository.nextListenerPriority();
      needsMetadataUpdate = true;
    }
    if (needsMetadataUpdate) {
      repository.updateAccessTokenAndPriority(id, accessToken, listenerPriority);
    }

    repository.updateStatus(id, SandboxStatus.PROVISIONING);

    SandboxInstance updated =
        existing.toBuilder()
            .accessToken(accessToken)
            .listenerPriority(listenerPriority)
            .status(SandboxStatus.PROVISIONING)
            .build();

    String dbUser = updated.getDatabaseName();
    String dbPassword = generateSecret(32);

    enqueueForSandbox(id, () -> reprovision(updated, dbUser, dbPassword));

    return CompletableFuture.completedFuture(Optional.of(updated));
  }

  /** The long-running half of creation. Runs on {@link #provisioningPool}. */
  private void provision(SandboxInstance instance, String dbUser, String dbPassword) {
    applyInfrastructure(
        instance,
        dbUser,
        dbPassword,
        () -> {
          provisionDatabase(instance.getDatabaseName(), dbUser, dbPassword);
          log.info("[{}] Database created", instance.getId());
        });
  }

  /**
   * The long-running half of re-provisioning. Updates the database role password in place (without
   * touching existing tables or data) so it matches the rotated Secrets Manager secret, then
   * re-runs {@code terraform apply} against the sandbox's existing state.
   */
  private void reprovision(SandboxInstance instance, String dbUser, String dbPassword) {
    applyInfrastructure(
        instance,
        dbUser,
        dbPassword,
        () -> {
          reconcileDatabase(instance.getDatabaseName(), dbUser, dbPassword);
          log.info("[{}] Database credentials reconciled", instance.getId());
        });
  }

  private void applyInfrastructure(
      SandboxInstance instance, String dbUser, String dbPassword, Runnable databaseStep) {
    String id = instance.getId();
    Path workDir = null;

    try {
      log.info("[{}] Provisioning started (priority={})", id, instance.getListenerPriority());

      // 1. Ensure the database and role exist with the expected password before the
      //    container starts or restarts.
      databaseStep.run();

      // 2. Fresh working directory. Terraform keeps provider plugins and backend
      //    configuration in .terraform/ inside the working directory, so concurrent
      //    jobs sharing one would fight over both.
      workDir = createWorkspace(id);

      Path varFile = workDir.resolve("sandbox.tfvars.json");
      writeTfVars(varFile, instance, dbUser, dbPassword);

      // 3. Per-sandbox state key: independent locking, independent failure.
      terraform.init(workDir, stateBucket, stateKey(id), awsRegion);
      log.info("[{}] Terraform initialised", id);

      terraform.apply(workDir, varFile, line -> log.debug("[{}] {}", id, line));
      log.info("[{}] Terraform apply complete", id);

      // 4. Record what was actually created, not what we predicted.
      Map<String, JsonNode> out = terraform.outputs(workDir);
      String sandboxUrl = out.get("sandbox_url").asText();
      repository.updateTerraformOutputs(
          id,
          sandboxUrl,
          out.get("target_group_arn").asText(),
          out.get("listener_rule_arn").asText(),
          out.get("task_definition_arn").asText());

      // 5. Wait for CiviForm to finish booting and serve 200 OK on /programs before
      //    marking the sandbox RUNNING.
      waitForProgramsReady(id, sandboxUrl, instance.getAccessToken());

      // If a delete was requested while apply or readiness polling was running,
      // leave DELETING in place — the queued destroy runs next.
      if (!isDeleting(id)) {
        repository.updateStatus(id, SandboxStatus.RUNNING);
        log.info("[{}] Status → RUNNING at {}", id, sandboxUrl);
      }

    } catch (Exception e) {
      // Status only. The row, the state file, and any half-created AWS resources are
      // all left in place: a failed provision needs to be diagnosed and either retried
      // or destroyed, and discarding the state would strand whatever did get created
      // with no record that it exists.
      log.error("[{}] Provisioning failed: {}", id, e.getMessage(), e);
      if (!isDeleting(id)) {
        repository.updateStatus(id, SandboxStatus.FAILED);
      }
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    Optional<SandboxInstance> maybeSandbox = repository.findById(id);
    if (maybeSandbox.isEmpty()) {
      return CompletableFuture.completedFuture(false);
    }
    SandboxInstance sandbox = maybeSandbox.get();

    // A DELETED row is a tombstone: everything is already gone. A DELETING row already has
    // a teardown running in the background; launching a second one would collide on the S3
    // state lock (HTTP 412 PreconditionFailed on the .tflock object) and flip the row to
    // DELETE_FAILED while the first teardown is still succeeding.
    if (sandbox.getStatus() == SandboxStatus.DELETED
        || sandbox.getStatus() == SandboxStatus.DELETING) {
      return CompletableFuture.completedFuture(false);
    }

    // Persisted before returning, matching createSandbox: the redirect renders DELETING
    // immediately instead of blocking the HTTP response for ~3 minutes while the Fargate
    // task's ENI detaches, and any duplicate delete request is rejected by the guard above.
    repository.updateStatus(id, SandboxStatus.DELETING);

    enqueueForSandbox(id, () -> destroy(sandbox));

    return CompletableFuture.completedFuture(true);
  }

  /** The long-running half of deletion. Runs on {@link #provisioningPool}. */
  private void destroy(SandboxInstance sandbox) {
    String id = sandbox.getId();
    Path workDir = null;

    try {
      log.info("[{}] Teardown started", id);
      workDir = createWorkspace(id);
      Path varFile = workDir.resolve("sandbox.tfvars.json");
      writeTfVars(varFile, sandbox, sandbox.getDatabaseName(), DESTROY_PLACEHOLDER_PASSWORD);

      terraform.init(workDir, stateBucket, stateKey(id), awsRegion);
      terraform.destroy(workDir, varFile, line -> log.debug("[{}] {}", id, line));
      log.info("[{}] Terraform destroy complete", id);

    } catch (Exception e) {
      // Keep the row. It holds the database name and listener priority, which are
      // the only records of what is still running in AWS.
      log.error("[{}] Terraform destroy failed: {}", id, e.getMessage(), e);
      repository.updateStatus(id, SandboxStatus.DELETE_FAILED);
      return;
    } finally {
      deleteRecursively(workDir);
    }

    // Dropped after destroy, not before: the ECS task holds connections open and
    // DROP DATABASE would contend with them.
    try {
      dropDatabase(sandbox.getDatabaseName());
      log.info("[{}] Database dropped", id);
    } catch (Exception e) {
      log.error(
          "[{}] Could not drop database, keeping record for retry: {}", id, e.getMessage(), e);
      repository.updateStatus(id, SandboxStatus.DELETE_FAILED);
      return;
    }

    repository.softDelete(id, Instant.now());
  }

  /**
   * Schedules {@code work} on {@link #provisioningPool}, chained after any job already running for
   * the same sandbox.
   *
   * <p>Different sandboxes have independent state keys and run concurrently. Two jobs for the same
   * sandbox — e.g. a delete requested while provisioning is still mid-apply — share a state lock
   * and must run sequentially rather than race against S3.
   */
  private void enqueueForSandbox(String id, Runnable work) {
    CompletableFuture<Void> next =
        inFlight.compute(
            id,
            (k, previous) ->
                previous == null
                    ? CompletableFuture.runAsync(work, provisioningPool)
                    : previous.handleAsync(
                        (ignored, ex) -> {
                          work.run();
                          return null;
                        },
                        provisioningPool));
    next.whenComplete((r, e) -> inFlight.remove(id, next));
  }

  private boolean isDeleting(String id) {
    return repository.findById(id).map(s -> s.getStatus() == SandboxStatus.DELETING).orElse(false);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days) {
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<SandboxInstance> maybeSandbox = repository.findById(id);
          if (maybeSandbox.isEmpty()) {
            return Optional.empty();
          }
          SandboxInstance existing = maybeSandbox.get();
          Instant extended = existing.getExpiresAt().plus(Duration.ofDays(days));

          repository.updateExpiry(id, extended);
          log.info("[{}] Expiry extended by {} days → {}", id, days, extended);

          return Optional.of(existing.toBuilder().expiresAt(extended).build());
        },
        provisioningPool);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin) {
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<SandboxInstance> maybeSandbox = repository.findById(sandboxId);
          if (maybeSandbox.isEmpty()) {
            return Optional.<SandboxInstance>empty();
          }
          SandboxInstance sandbox = maybeSandbox.get();

          // An expired or torn-down sandbox must not be reachable even with the right PIN.
          if (sandbox.getStatus() == SandboxStatus.DELETED
              || sandbox.getStatus() == SandboxStatus.DELETING
              || sandbox.getExpiresAt().isBefore(Instant.now())) {
            return Optional.<SandboxInstance>empty();
          }

          boolean matches =
              MessageDigest.isEqual(
                  sandbox.getPin().getBytes(StandardCharsets.UTF_8),
                  pin.getBytes(StandardCharsets.UTF_8));

          return matches ? Optional.of(sandbox) : Optional.<SandboxInstance>empty();
        },
        provisioningPool);
  }

  // ---------------------------------------------------------------------------
  // Terraform inputs
  // ---------------------------------------------------------------------------

  /** Builds the per-sandbox variables file by merging platform outputs with sandbox values. */
  private void writeTfVars(
      Path varFile, SandboxInstance instance, String dbUser, String dbPassword) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode vars = mapper.createObjectNode();

    vars.put("sandbox_id", instance.getId());
    vars.put("city_name", instance.getCityName());
    vars.put("subdomain", instance.getSubdomain());
    vars.put("admin_email", instance.getAdminEmail());
    vars.put("listener_priority", instance.getListenerPriority());
    vars.put("access_token", instance.getAccessToken());
    vars.put("civiform_image_tag", instance.getCiviformVersion());

    vars.put("db_address", platformOutput("rds_endpoint").asText());
    vars.put("db_name", instance.getDatabaseName());
    vars.put("db_username", dbUser);
    vars.put("db_password", dbPassword);

    // Copied straight through from the platform stack. Names match the platform
    // outputs so a mismatch is a missing-key error here rather than a confusing
    // Terraform failure several minutes into an apply.
    for (String key :
        new String[] {
          "base_domain",
          "vpc_id",
          "private_subnet_ids",
          "alb_security_group_id",
          "alb_listener_arn",
          "ecs_cluster_arn",
          "ecs_cluster_name",
          "log_group_name",
          "kms_key_arn",
          "placeholder_secrets",
        }) {
      vars.set(key, platformOutput(platformOutputKey(key)));
    }

    try {
      Files.writeString(varFile, mapper.writeValueAsString(vars), StandardCharsets.UTF_8);

      // Contains the database password in plaintext. The file lives in a temp dir that
      // is deleted in the finally block, but narrow the window anyway — on a shared
      // host the default umask may leave it group-readable.
      varFile.toFile().setReadable(false, false);
      varFile.toFile().setReadable(true, true);

    } catch (IOException e) {
      throw new RuntimeException("Could not write " + varFile, e);
    }
  }

  /**
   * Maps a sandbox variable name to the platform output that supplies it.
   *
   * <p>Most match exactly. The two that do not are named for their role in the platform stack
   * rather than in a sandbox, and renaming either one would be a breaking change to a stack that is
   * already applied.
   */
  private static String platformOutputKey(String sandboxVar) {
    return switch (sandboxVar) {
      case "alb_listener_arn" -> "alb_https_listener_arn";
      case "kms_key_arn" -> "sandbox_secrets_kms_key_arn";
      case "log_group_name" -> "cloudwatch_log_group";
      default -> sandboxVar;
    };
  }

  private JsonNode platformOutput(String key) {
    JsonNode value = platformOutputs.get(key);
    if (value == null) {
      throw new IllegalStateException(
          "Platform output '"
              + key
              + "' is missing. Regenerate the platform outputs file with:\n"
              + "  terraform -chdir=terraform output -json > <platformOutputsFile>");
    }
    return value;
  }

  /**
   * Loads the platform stack's outputs from inline JSON ({@code
   * sandbox.terraform.platformOutputsJson}) when present, falling back to the file path in {@code
   * sandbox.terraform.platformOutputsFile}.
   *
   * <p>Accepts both Terraform's {@code {value, type, sensitive}} envelope (produced by {@code
   * terraform output -json}) and a flat {@code {key: value}} object (convenient when injected by
   * the platform Terraform stack into the builder ECS task definition).
   */
  private static Map<String, JsonNode> loadPlatformOutputs(Config config) {
    String inlineJson =
        config.hasPath("sandbox.terraform.platformOutputsJson")
            ? config.getString("sandbox.terraform.platformOutputsJson").trim()
            : "";

    if (!inlineJson.isEmpty()) {
      try {
        return parsePlatformOutputsJson(inlineJson);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Could not parse inline platform outputs from sandbox.terraform.platformOutputsJson",
            e);
      }
    }

    String path = config.getString("sandbox.terraform.platformOutputsFile");
    try {
      String raw = Files.readString(Path.of(path), StandardCharsets.UTF_8);
      return parsePlatformOutputsJson(raw);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Could not read platform outputs from "
              + path
              + ". Generate it with: terraform -chdir=terraform output -json > "
              + path,
          e);
    }
  }

  private static Map<String, JsonNode> parsePlatformOutputsJson(String rawJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(rawJson);
    Map<String, JsonNode> outputs = new java.util.LinkedHashMap<>();
    root.fields()
        .forEachRemaining(
            e -> {
              JsonNode node = e.getValue();
              // Unwrap Terraform's {value, type, sensitive} envelope when present;
              // otherwise use the bare value directly.
              JsonNode unwrapped =
                  node != null && node.isObject() && node.has("value") ? node.get("value") : node;
              outputs.put(e.getKey(), unwrapped);
            });
    return outputs;
  }

  // ---------------------------------------------------------------------------
  // Workspace
  // ---------------------------------------------------------------------------

  /**
   * Copies the sandbox root module into a fresh temp directory.
   *
   * <p>Copied rather than used in place because {@code terraform init} writes {@code .terraform/}
   * and a backend configuration into the working directory. Two concurrent provisions running in
   * the repo's own {@code terraform/sandbox} would overwrite each other's backend config and end up
   * writing to the wrong state file.
   */
  private Path createWorkspace(String sandboxId) throws IOException {
    Path workDir = Files.createTempDirectory("tf-" + sandboxId + "-");

    try (Stream<Path> entries = Files.list(moduleDir)) {
      for (Path source : entries.toList()) {
        if (Files.isRegularFile(source)) {
          Files.copy(
              source, workDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    return workDir;
  }

  private void deleteRecursively(Path dir) {
    if (dir == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      // Not fatal: a leaked temp dir wastes disk but breaks nothing, and failing the
      // provision over it would turn a healthy sandbox into a reported failure.
      log.warn("Could not clean up {}: {}", dir, e.getMessage());
    }
  }

  private String stateKey(String sandboxId) {
    return "sandboxes/" + sandboxId + "/terraform.tfstate";
  }

  // ---------------------------------------------------------------------------
  // Database
  // ---------------------------------------------------------------------------

  /**
   * Creates the per-sandbox database and its owning role on the shared RDS instance.
   *
   * <p>A database rather than a schema: CiviForm applies Play evolutions and assumes it owns its
   * database, and {@code DROP DATABASE} makes teardown provable in a way that dropping a schema
   * does not.
   *
   * <p>Uses a direct JDBC connection rather than the builder's own pool, because the builder's
   * metadata database and the sandbox RDS instance are different servers in AWS.
   */
  private void provisionDatabase(String databaseName, String dbUser, String dbPassword) {
    withRdsAdmin(
        st -> {
          st.execute(String.format("CREATE USER %s WITH PASSWORD '%s'", dbUser, dbPassword));
          // CREATE DATABASE ... OWNER requires the creating role to be a member of the
          // owner role. A real superuser passes that check implicitly, but on RDS the
          // admin is only rds_superuser, so grant membership explicitly.
          st.execute(String.format("GRANT %s TO CURRENT_USER", dbUser));
          st.execute(String.format("CREATE DATABASE %s OWNER %s", databaseName, dbUser));
          // Postgres grants CONNECT and TEMPORARY to PUBLIC by default, which lets
          // any sandbox credential connect to any other sandbox's database. Revoking
          // these privileges closes the cross-sandbox isolation gap (#16).
          st.execute(
              String.format(
                  "REVOKE CONNECT, TEMPORARY ON DATABASE %s FROM PUBLIC", databaseName));
        });

    // Extensions are per-database, so this needs a connection to the new database.
    String url = rdsUrl(databaseName);
    try (Connection conn = DriverManager.getConnection(url, dbUser, dbPassword);
        Statement st = conn.createStatement()) {
      st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm;");
      st.execute("CREATE EXTENSION IF NOT EXISTS btree_gin;");
    } catch (SQLException e) {
      throw new RuntimeException("Could not install extensions in " + databaseName, e);
    }
  }

  /**
   * Ensures the per-sandbox database and role exist and updates the role's password to {@code
   * dbPassword} without modifying existing tables or data.
   *
   * <p>The builder does not persist the sandbox's database password — it lives only in Secrets
   * Manager and Terraform state. On re-provision, generating a fresh password and updating the role
   * via {@code ALTER USER} keeps PostgreSQL and Secrets Manager in sync while preserving all
   * existing database contents. If a previous initial provision failed before the database was
   * created, this falls back to creating the role and database cleanly.
   */
  private void reconcileDatabase(String databaseName, String dbUser, String dbPassword) {
    boolean[] dbExists = {false};
    withRdsAdmin(
        st -> {
          boolean roleExists;
          try (java.sql.ResultSet rs =
              st.executeQuery(
                  String.format("SELECT 1 FROM pg_roles WHERE rolname = '%s'", dbUser))) {
            roleExists = rs.next();
          }
          if (roleExists) {
            st.execute(String.format("ALTER USER %s WITH PASSWORD '%s'", dbUser, dbPassword));
          } else {
            st.execute(String.format("CREATE USER %s WITH PASSWORD '%s'", dbUser, dbPassword));
          }
          st.execute(String.format("GRANT %s TO CURRENT_USER", dbUser));

          try (java.sql.ResultSet rs =
              st.executeQuery(
                  String.format("SELECT 1 FROM pg_database WHERE datname = '%s'", databaseName))) {
            dbExists[0] = rs.next();
          }
          if (!dbExists[0]) {
            st.execute(String.format("CREATE DATABASE %s OWNER %s", databaseName, dbUser));
            st.execute(
                String.format(
                    "REVOKE CONNECT, TEMPORARY ON DATABASE %s FROM PUBLIC", databaseName));
          }
        });

    if (!dbExists[0]) {
      String url = rdsUrl(databaseName);
      try (Connection conn = DriverManager.getConnection(url, dbUser, dbPassword);
          Statement st = conn.createStatement()) {
        st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm;");
        st.execute("CREATE EXTENSION IF NOT EXISTS btree_gin;");
      } catch (SQLException e) {
        throw new RuntimeException("Could not install extensions in " + databaseName, e);
      }
    }
  }

  private void dropDatabase(String databaseName) {
    withRdsAdmin(
        st -> {
          // WITH (FORCE) terminates remaining connections and drops in one statement,
          // closing the window where a client reconnects between a separate
          // pg_terminate_backend and the drop.
          st.execute(String.format("DROP DATABASE IF EXISTS %s WITH (FORCE)", databaseName));
          st.execute(String.format("DROP USER IF EXISTS %s", databaseName));
        });
  }

  private void withRdsAdmin(SqlWork work) {
    String url = rdsUrl(config.getString("sandbox.rds.adminDatabase"));
    try (Connection conn =
            DriverManager.getConnection(
                url,
                config.getString("sandbox.rds.adminUser"),
                config.getString("sandbox.rds.adminPassword"));
        Statement st = conn.createStatement()) {
      work.run(st);
    } catch (SQLException e) {
      throw new RuntimeException("RDS admin operation failed", e);
    }
  }

  /**
   * Host and port for the builder's own administrative connection.
   *
   * <p>Defaults to the shared RDS endpoint on 5432, which is correct whenever the builder runs
   * inside the VPC — the production topology. Overridable because the builder's connection and the
   * sandbox task's connection need not take the same route: a developer reaches RDS through an SSM
   * port-forward on localhost, while the task always uses the real endpoint from inside the private
   * subnets.
   *
   * <p>This is why the override exists at all rather than simply editing the platform outputs file.
   * {@code rds_endpoint} is also passed to the sandbox stack as {@code db_address}, so pointing it
   * at a tunnel would tell the ECS task to connect to the developer's laptop.
   *
   * <p>Resolved on each use rather than in the constructor: an incomplete outputs file must surface
   * when a sandbox is created, not refuse to start the whole application.
   */
  private String rdsAdminHost() {
    String configured = config.getString("sandbox.rds.adminHost");
    return configured.isBlank() ? platformOutput("rds_endpoint").asText() : configured;
  }

  @VisibleForTesting
  String rdsUrl(String databaseName) {
    return String.format(
        "jdbc:postgresql://%s:%d/%s?ssl=true&sslmode=require",
        rdsAdminHost(), config.getInt("sandbox.rds.adminPort"), databaseName);
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Statement statement) throws SQLException;
  }

  // ---------------------------------------------------------------------------
  // Readiness polling
  // ---------------------------------------------------------------------------

  /**
   * Polls {@code <sandboxUrl>/programs} until it returns HTTP 200, the sandbox is marked for
   * deletion, or {@link #readinessTimeout} elapses.
   *
   * <p>{@code terraform apply} finishes as soon as the ECS service and ALB listener rule exist,
   * before Fargate has pulled the image, booted CiviForm, run database evolutions, and passed ALB
   * target group health checks. Waiting for {@code /programs} to return 200 ensures the portal
   * does not show the sandbox as Active while the ALB is still returning 502/503.
   *
   * <p>Includes the {@code sb_access_<id>=<accessToken>} cookie so the ALB forwards the request to
   * the CiviForm target group instead of redirecting to the PIN gate on the builder portal.
   */
  private void waitForProgramsReady(String id, String sandboxUrl, String accessToken) {
    waitForProgramsReady(
        id, sandboxUrl, accessToken, this::probeUrl, d -> Thread.sleep(d.toMillis()));
  }

  @VisibleForTesting
  void waitForProgramsReady(
      String id,
      String sandboxUrl,
      String accessToken,
      HttpStatusProbe probe,
      Sleeper sleeper) {
    String programsUrl =
        sandboxUrl.endsWith("/") ? sandboxUrl + "programs" : sandboxUrl + "/programs";
    String cookieName = accessCookieName(id);
    Instant deadline = Instant.now().plus(readinessTimeout);
    int attempt = 0;

    log.info(
        "[{}] Waiting up to {}m for {} to return 200",
        id,
        readinessTimeout.toMinutes(),
        programsUrl);

    while (!isDeleting(id)) {
      attempt++;
      try {
        int status = probe.getStatus(programsUrl, cookieName, accessToken);
        if (status == 200) {
          log.info("[{}] {} returned 200 on attempt {}", id, programsUrl, attempt);
          return;
        }
        log.debug(
            "[{}] Readiness attempt {} for {} returned HTTP {}",
            id,
            attempt,
            programsUrl,
            status);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            "Interrupted waiting for " + programsUrl + " to become ready", e);
      } catch (Exception e) {
        log.debug(
            "[{}] Readiness attempt {} for {} failed: {}",
            id,
            attempt,
            programsUrl,
            e.getMessage());
      }

      if (!Instant.now().isBefore(deadline)) {
        throw new RuntimeException(
            String.format(
                "Timed out after %d minutes (%d attempts) waiting for %s to return 200",
                readinessTimeout.toMinutes(), attempt, programsUrl));
      }

      try {
        sleeper.sleep(readinessPollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            "Interrupted waiting for " + programsUrl + " to become ready", e);
      }
    }

    log.info("[{}] Aborting readiness poll because sandbox is deleting", id);
  }

  /**
   * Sends a GET request to {@code url} with the sandbox PIN-gate bypass cookie pre-populated in a
   * per-request cookie jar, following redirects so CiviForm's guest session redirect chain
   * ({@code /programs} → {@code /callback?client_name=GuestClient} → {@code /programs}) resolves
   * to its final HTTP status code without being redirected to the PIN gate by the ALB.
   */
  @VisibleForTesting
  int probeUrl(String url, String cookieName, String cookieValue)
      throws IOException, InterruptedException {
    URI uri = URI.create(url);
    CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    if (cookieName != null && cookieValue != null) {
      HttpCookie accessCookie = new HttpCookie(cookieName, cookieValue);
      accessCookie.setPath("/");
      accessCookie.setVersion(0);
      cookieManager.getCookieStore().add(uri, accessCookie);
    }
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookieManager)
            .build();
    HttpRequest request =
        HttpRequest.newBuilder(uri).timeout(HTTP_PROBE_TIMEOUT).GET().build();
    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
    return response.statusCode();
  }

  /**
   * Name of the ALB PIN-gate access cookie for a sandbox. Matches {@code
   * aws_lb_listener_rule.sandbox_allow} in {@code terraform/sandbox/main.tf} and {@code
   * SandboxController.accessCookieName}.
   */
  @VisibleForTesting
  static String accessCookieName(String sandboxId) {
    return "sb_access_" + sandboxId.replace("-", "_");
  }

  @VisibleForTesting
  @FunctionalInterface
  interface HttpStatusProbe {
    int getStatus(String url, String cookieName, String cookieValue) throws Exception;
  }

  @VisibleForTesting
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /** Alphanumeric only: the value ends up in a JDBC URL, a SQL literal and a JSON file. */
  private static String generateSecret(int length) {
    return RandomStringUtils.secure().nextAlphanumeric(length);
  }
}
