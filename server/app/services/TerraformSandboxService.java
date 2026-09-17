package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.typesafe.config.Config;
import java.io.IOException;
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

  private final Map<String, JsonNode> platformOutputs;

  /** Dedicated pool. Terraform runs are long and blocking; Play's pool must not be used. */
  private final Executor provisioningPool = Executors.newCachedThreadPool();

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

    this.platformOutputs = loadPlatformOutputs(config.getString("sandbox.terraform.platformOutputsFile"));
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

    CompletableFuture.runAsync(() -> provision(instance, dbUser, dbPassword), provisioningPool);

    return CompletableFuture.completedFuture(instance);
  }

  /** The long-running half of creation. Runs on {@link #provisioningPool}. */
  private void provision(SandboxInstance instance, String dbUser, String dbPassword) {
    String id = instance.getId();
    Path workDir = null;

    try {
      log.info("[{}] Provisioning started (priority={})", id, instance.getListenerPriority());

      // 1. The database must exist before the container starts. CiviForm connects on
      //    boot, and the ECS deployment circuit breaker turns a failed connection into
      //    a failed deployment after 10 attempts rather than retrying indefinitely —
      //    so a late database is not slow, it is fatal.
      provisionDatabase(instance.getDatabaseName(), dbUser, dbPassword);
      log.info("[{}] Database created", id);

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
      repository.updateTerraformOutputs(
          id,
          out.get("sandbox_url").asText(),
          out.get("target_group_arn").asText(),
          out.get("listener_rule_arn").asText(),
          out.get("task_definition_arn").asText());

      repository.updateStatus(id, SandboxStatus.RUNNING);
      log.info("[{}] Status → RUNNING at {}", id, out.get("sandbox_url").asText());

    } catch (Exception e) {
      // Status only. The row, the state file, and any half-created AWS resources are
      // all left in place: a failed provision needs to be diagnosed and either retried
      // or destroyed, and discarding the state would strand whatever did get created
      // with no record that it exists.
      log.error("[{}] Provisioning failed: {}", id, e.getMessage(), e);
      repository.updateStatus(id, SandboxStatus.FAILED);
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<SandboxInstance> maybeSandbox = repository.findById(id);
          if (maybeSandbox.isEmpty()) {
            return false;
          }
          SandboxInstance sandbox = maybeSandbox.get();

          // A DELETED row is a tombstone: everything is already gone. Re-running teardown
          // would fail the DROP DATABASE and flip the tombstone to DELETE_FAILED, which
          // falsely signals that something is still out there to clean up.
          if (sandbox.getStatus() == SandboxStatus.DELETED) {
            return false;
          }

          Path workDir = null;
          try {
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
            return false;
          } finally {
            deleteRecursively(workDir);
          }

          // Dropped after destroy, not before: the ECS task holds connections open and
          // DROP DATABASE would contend with them.
          try {
            dropDatabase(sandbox.getDatabaseName());
            log.info("[{}] Database dropped", id);
          } catch (Exception e) {
            log.error("[{}] Could not drop database, keeping record for retry: {}", id, e.getMessage(), e);
            repository.updateStatus(id, SandboxStatus.DELETE_FAILED);
            return false;
          }

          return repository.softDelete(id, Instant.now());
        },
        provisioningPool);
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
   * Loads the platform stack's outputs from a JSON file.
   *
   * <p>A file produced by {@code terraform output -json}, rather than a dozen individual
   * configuration keys. The values are already published as Terraform outputs, so copying them into
   * configuration by hand would create a second source of truth that silently drifts the first time
   * the platform stack is reapplied.
   */
  private static Map<String, JsonNode> loadPlatformOutputs(String path) {
    try {
      JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(path), StandardCharsets.UTF_8));

      Map<String, JsonNode> outputs = new java.util.LinkedHashMap<>();
      // Unwrap Terraform's {value, type, sensitive} envelope.
      root.fields().forEachRemaining(e -> outputs.put(e.getKey(), e.getValue().get("value")));
      return outputs;

    } catch (IOException e) {
      throw new IllegalStateException(
          "Could not read platform outputs from "
              + path
              + ". Generate it with: terraform -chdir=terraform output -json > "
              + path,
          e);
    }
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
          Files.copy(source, workDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
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

  private String rdsUrl(String databaseName) {
    return String.format(
        "jdbc:postgresql://%s:5432/%s?ssl=true&sslmode=require",
        platformOutput("rds_endpoint").asText(), databaseName);
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Statement statement) throws SQLException;
  }

  /** Alphanumeric only: the value ends up in a JDBC URL, a SQL literal and a JSON file. */
  private static String generateSecret(int length) {
    return RandomStringUtils.secure().nextAlphanumeric(length);
  }
}
