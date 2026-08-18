package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.google.common.collect.ImmutableList;
import com.typesafe.config.Config;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import models.SandboxStatus;
import org.apache.commons.lang3.RandomStringUtils;
import play.Logger;
import play.Logger.ALogger;
import play.db.Database;
import play.libs.ws.WSClient;


/**
 * Real {@link SandboxService} implementation that launches CiviForm containers
 * via the Docker socket. Replaces {@link InMemorySandboxService} for Sprint 1.
 *
 * <p>Sprint 2 note: replace with {@code EcsFargateSandboxService}. The interface
 * contract is identical — only this class changes.
 */
@Singleton
public class DockerSandboxService implements SandboxService {

  private static final ALogger log = Logger.of(DockerSandboxService.class);

  private static final String CIVIFORM_INTERNAL_PORT = "9000/tcp";
  private static final int HEALTH_CHECK_MAX_ATTEMPTS = 40; // 40 × 5s = 200s max
  private static final int HEALTH_CHECK_INTERVAL_MS = 5_000;
  private static final int POST_HEALTH_BUFFER_MS = 15_000; // wait for DB migrations

  private final SandboxRepository repository;
  private final Database db;
  private final WSClient ws;
  private final DockerClient dockerClient;
  private final String civiformImage;
  private final String dbHost;

  /** Background thread pool for async provisioning (separate from Play HTTP pool). */
  private final Executor provisioningPool = Executors.newCachedThreadPool();

  @Inject
  public DockerSandboxService(
      SandboxRepository repository,
      Database db,
      WSClient ws,
      Config config) {
    this.repository = checkNotNull(repository);
    this.db = checkNotNull(db);
    this.ws = checkNotNull(ws);
    this.civiformImage = config.getString("sandbox.civiformImage");
    this.dbHost = config.getString("sandbox.dbHost");
    this.dockerClient = buildDockerClient(config.getString("docker.socketPath"));
  }

  /**
   * Constructs the {@link DockerClient} from the given socket path.
   *
   * <p>Protected and non-final so tests can subclass and return a mock client without
   * requiring a real Docker socket to be present in the CI environment.
   */
  protected DockerClient buildDockerClient(String socketPath) {
    DockerClientConfig dockerConfig = DefaultDockerClientConfig
        .createDefaultConfigBuilder()
        .build();
    ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(URI.create(socketPath))
        .maxConnections(20)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(60))
        .build();
    return DockerClientImpl.getInstance(dockerConfig, httpClient);
  }

  @Override
  public CompletionStage<ImmutableList<SandboxInstance>> listSandboxes() {
    return CompletableFuture.supplyAsync(repository::findAll, provisioningPool);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> getSandbox(String id) {
    return CompletableFuture.supplyAsync(() -> repository.findById(id), provisioningPool);
  }

  /**
   * Creates a sandbox synchronously up to DB persistence, then launches the
   * Docker container asynchronously. Returns immediately with PROVISIONING status.
   * The PIN is available in the returned instance — show it to the sales rep now.
   */
  @Override
  public CompletionStage<SandboxInstance> createSandbox(
      String cityName, String version, String adminEmail, String notes) {

    String id = "sb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String schemaName = "sandbox_" + id.replace("-", "_");
    String pin = generatePin();
    String dbUser = schemaName;
    String dbPassword = generateSecret(20);
    String appSecret = generateSecret(32);
    String imageTag = (version != null && !version.isBlank()) ? version : "latest";

    // Allocate port atomically via Postgres sequence (thread-safe)
    int hostPort = repository.nextPort();

    SandboxInstance instance = SandboxInstance.builder()
        .id(id)
        .cityName(cityName)
        .civiformVersion(imageTag)
        .status(SandboxStatus.PROVISIONING)
        .url("http://localhost:" + hostPort)
        .adminEmail(adminEmail != null ? adminEmail : "")
        .notes(notes != null ? notes : "")
        .pin(pin)
        .hostPort(hostPort)
        .schemaName(schemaName)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)))
        .build();

    // Persist before async work — PIN is visible immediately
    repository.save(instance);

    // Launch container asynchronously
    CompletableFuture.runAsync(() -> {
      try {
        log.info("[{}] Provisioning started (port={}, schema={})", id, hostPort, schemaName);

        // 1. Create per-sandbox Postgres schema before launching container
        provisionSchema(schemaName, dbUser, dbPassword);
        log.info("[{}] Schema provisioned", id);

        // 2. Build JDBC URL accessible from inside the container
        String dbUrl = String.format(
            "jdbc:postgresql://%s:5432/sandbox_builder?currentSchema=%s",
            dbHost, schemaName);

        // 3. Launch the CiviForm Docker container
        String containerId = launchContainer(id, imageTag, hostPort, dbUrl, dbUser, dbPassword, appSecret, cityName);
        repository.updateContainerId(id, containerId);
        log.info("[{}] Container launched: {}", id, containerId);

        // 4. Wait for CiviForm /health to pass, then add buffer for DB migrations
        waitForHealthy(id, hostPort);
        log.info("[{}] Health check passed, waiting {}ms for DB migrations", id, POST_HEALTH_BUFFER_MS);
        Thread.sleep(POST_HEALTH_BUFFER_MS);

        // 5. Mark RUNNING
        repository.updateStatus(id, SandboxStatus.RUNNING);
        log.info("[{}] Status → RUNNING", id);

      } catch (Exception e) {
        log.error("[{}] Provisioning failed: {}", id, e.getMessage(), e);
        repository.updateStatus(id, SandboxStatus.FAILED);
      }
    }, provisioningPool);

    return CompletableFuture.completedFuture(instance);
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    return CompletableFuture.supplyAsync(() -> {
      Optional<SandboxInstance> maybeSandbox = repository.findById(id);
      if (maybeSandbox.isEmpty()) {
        return false;
      }
      SandboxInstance sandbox = maybeSandbox.get();

      // Stop and remove container
      if (sandbox.getContainerId() != null) {
        try {
          dockerClient.stopContainerCmd(sandbox.getContainerId())
              .withTimeout(10)
              .exec();
          dockerClient.removeContainerCmd(sandbox.getContainerId())
              .withForce(true)
              .exec();
          log.info("[{}] Container stopped and removed", id);
        } catch (Exception e) {
          log.warn("[{}] Could not stop container: {}", id, e.getMessage());
        }
      }

      // Drop the per-sandbox schema
      try {
        dropSchema(sandbox.getSchemaName());
        log.info("[{}] Schema dropped", id);
      } catch (Exception e) {
        log.warn("[{}] Could not drop schema: {}", id, e.getMessage());
      }

      return repository.delete(id);
    }, provisioningPool);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin) {
    return CompletableFuture.supplyAsync(() -> {
      Optional<SandboxInstance> maybeSandbox = repository.findById(sandboxId);
      if (maybeSandbox.isEmpty()) {
        return Optional.empty();
      }
      SandboxInstance sandbox = maybeSandbox.get();

      // Constant-time comparison to prevent timing attacks
      boolean matches = MessageDigest.isEqual(
          sandbox.getPin().getBytes(),
          pin.getBytes());

      return matches ? Optional.of(sandbox) : Optional.empty();
    }, provisioningPool);
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days) {
    return CompletableFuture.supplyAsync(() -> {
      Optional<SandboxInstance> maybeSandbox = repository.findById(id);
      if (maybeSandbox.isEmpty()) {
        return Optional.empty();
      }
      SandboxInstance existing = maybeSandbox.get();
      SandboxInstance extended = existing.toBuilder()
          .expiresAt(existing.getExpiresAt().plus(Duration.ofDays(days)))
          .build();
      repository.save(extended);
      log.info("[{}] Sandbox extended by {} days. New expiry: {}", id, days, extended.getExpiresAt());
      return Optional.of(extended);
    }, provisioningPool);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /** Provisions a dedicated Postgres schema and user for a sandbox. */
  private void provisionSchema(String schemaName, String dbUser, String dbPassword) {
    db.withConnection(conn -> {
      try (Statement st = conn.createStatement()) {
        st.execute(String.format(
            "CREATE USER %s WITH PASSWORD '%s'", dbUser, dbPassword));
        st.execute(String.format(
            "CREATE SCHEMA %s AUTHORIZATION %s", schemaName, dbUser));
        // Grant connect on the database
        st.execute(String.format(
            "GRANT CONNECT ON DATABASE sandbox_builder TO %s", dbUser));
      }
      return null;
    });
  }

  /** Drops the schema and user for a deleted sandbox. */
  private void dropSchema(String schemaName) {
    db.withConnection(conn -> {
      try (Statement st = conn.createStatement()) {
        st.execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", schemaName));
        st.execute(String.format("DROP USER IF EXISTS %s", schemaName));
      }
      return null;
    });
  }

  /** Launches a CiviForm container and returns its container ID. */
  private String launchContainer(
      String sandboxId, String imageTag, int hostPort,
      String dbUrl, String dbUser, String dbPassword,
      String appSecret, String cityName) {

    ExposedPort internalPort = ExposedPort.tcp(9000);
    Ports portBindings = new Ports();
    portBindings.bind(internalPort, Ports.Binding.bindPort(hostPort));

    CreateContainerResponse container = dockerClient
        .createContainerCmd(civiformImage.replace(":latest", ":" + imageTag))
        .withName("civiform-sandbox-" + sandboxId)
        .withExposedPorts(internalPort)
        .withHostConfig(HostConfig.newHostConfig()
            .withPortBindings(portBindings)
            .withNetworkMode("bridge")
            // Ensure host.docker.internal resolves on Linux (Docker 20.10+)
            // On Mac this is a no-op; on Linux it maps to the bridge gateway (e.g. 172.17.0.1)
            .withExtraHosts("host.docker.internal:host-gateway"))
        .withEnv(
            "DATABASE_URL=" + dbUrl,
            "DATABASE_USERNAME=" + dbUser,
            "DATABASE_PASSWORD=" + dbPassword,
            "APPLICATION_SECRET=" + appSecret,
            "STAGING_DISABLE_DEMO_MODE_LOGINS=false",
            "PORT=9000")
        .exec();

    dockerClient.startContainerCmd(container.getId()).exec();
    return container.getId();
  }

  /**
   * Polls CiviForm's /health endpoint until it returns 200, or throws after max attempts.
   * Uses WSClient so the HTTP call is non-blocking relative to Play's pool.
   */
  private void waitForHealthy(String sandboxId, int hostPort) throws Exception {
    String healthUrl = "http://localhost:" + hostPort + "/health";
    for (int attempt = 1; attempt <= HEALTH_CHECK_MAX_ATTEMPTS; attempt++) {
      try {
        int status = ws.url(healthUrl)
            .setRequestTimeout(Duration.ofSeconds(4))
            .get()
            .toCompletableFuture()
            .get()
            .getStatus();
        if (status == 200) {
          log.info("[{}] Health check passed on attempt {}", sandboxId, attempt);
          return;
        }
      } catch (Exception e) {
        log.debug("[{}] Health check attempt {} failed: {}", sandboxId, attempt, e.getMessage());
      }
      Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
    }
    throw new RuntimeException("CiviForm container never became healthy for sandbox " + sandboxId);
  }

  /** Generates a cryptographically random 6-digit PIN. */
  private String generatePin() {
    SecureRandom rng = new SecureRandom();
    return String.format("%06d", rng.nextInt(1_000_000));
  }

  /** Generates a cryptographically random alphanumeric secret of the given length. */
  private String generateSecret(int length) {
    return RandomStringUtils.random(length, 0, 0, true, true, null, new SecureRandom());
  }
}
