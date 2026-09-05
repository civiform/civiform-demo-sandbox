package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.typesafe.config.Config;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.LogDriver;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Condition;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateRuleRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteRuleRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeregisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ForwardActionConfig;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.HostHeaderConditionConfig;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.IpAddressType;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Matcher;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Protocol;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupTuple;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Sprint 2 implementation of {@link SandboxService} using AWS ECS Fargate.
 *
 * <p>Replaces {@link DockerSandboxService} (Sprint 1 Docker socket) with cloud-hosted containers.
 * The {@link SandboxService} interface, all controller logic, PIN gate, and DB schema are
 * identical — only the runtime call changes.
 *
 * <p><b>Wildcard subdomain design</b>: Each sandbox gets its own subdomain under the wildcard
 * cert {@code *.sandbox.civiform.dev}. "Burlington, VT" → {@code burlington-vt.sandbox.civiform.dev}.
 * Multiple sandboxes can be active concurrently — each has a unique ALB listener rule (host-header
 * match) routing to its own target group and ECS task.
 *
 * <p>This matches Rocky's architecture: one wildcard ACM cert, one ALB, per-sandbox listener
 * rules created/deleted dynamically by this service.
 *
 * <p>Required config keys (application.conf / env vars):
 * <pre>
 *   sandbox.domain             = "sandbox.civiform.dev"          (SANDBOX_DOMAIN)
 *   sandbox.aws.region         = "us-east-1"                     (AWS_REGION)
 *   sandbox.ecs.cluster        = "civiform-sandbox-cluster"      (ECS_CLUSTER)
 *   sandbox.ecs.subnets        = ["subnet-xxx","subnet-yyy"]     (ECS_SUBNETS)
 *   sandbox.ecs.security_group = "sg-xxx"                        (ECS_SECURITY_GROUP)
 *   sandbox.ecs.execution_role = "arn:aws:iam::...ExecutionRole" (ECS_EXECUTION_ROLE_ARN)
 *   sandbox.ecs.task_role      = "arn:aws:iam::...TaskRole"      (ECS_TASK_ROLE_ARN)
 *   sandbox.ecs.log_group      = "/ecs/civiform-sandbox"         (ECS_LOG_GROUP)
 *   sandbox.ecs.task_cpu       = 512
 *   sandbox.ecs.task_memory    = 1024
 *   sandbox.alb.listener_arn   = "arn:aws:elasticloadbalancing:...listener/..." (ALB_LISTENER_ARN)
 *   sandbox.rds.host           = "civiform-sandbox-postgres.xxx.rds.amazonaws.com" (RDS_HOST)
 *   sandbox.rds.port           = 5432
 *   sandbox.rds.dbname         = "civiform_sandbox"              (RDS_DBNAME)
 *   sandbox.rds.master_secret  = "arn:aws:secretsmanager:..."   (RDS_MASTER_SECRET_ARN)
 *   sandbox.civiform_image     = "civiform/civiform:latest"      (CIVIFORM_IMAGE)
 * </pre>
 */
@Singleton
public class EcsFargateSandboxService implements SandboxService {

  private static final Logger logger = LoggerFactory.getLogger(EcsFargateSandboxService.class);

  // ALB listener rules use priority 1–50000. We use 100–49900 for sandbox rules,
  // leaving headroom at both ends for future static rules.
  private static final int LISTENER_RULE_PRIORITY_BASE = 100;

  private final SandboxRepository repository;
  private final Config config;
  private final Executor provisioner;

  // AWS clients — lazily initialised so tests can subclass and override
  private volatile EcsClient ecsClient;
  private volatile ElasticLoadBalancingV2Client elbClient;
  private volatile SecretsManagerClient secretsClient;

  @Inject
  public EcsFargateSandboxService(SandboxRepository repository, Config config) {
    this.repository = checkNotNull(repository);
    this.config = checkNotNull(config);
    this.provisioner = Executors.newFixedThreadPool(4);
  }

  // ── SandboxService interface ────────────────────────────────────────────────

  @Override
  public CompletionStage<ImmutableList<SandboxInstance>> listSandboxes() {
    return repository.listAll();
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> getSandbox(String id) {
    return repository.findById(id);
  }

  @Override
  public CompletionStage<SandboxInstance> createSandbox(
      String name, String version, String adminEmail, String notes) {

    String id = "sb-" + UUID.randomUUID().toString().substring(0, 8);
    String pin = generatePin();
    String slug = toSlug(name);
    String sandboxUrl = "https://" + slug + "." + config.getString("sandbox.domain");
    Instant now = Instant.now();

    SandboxInstance instance =
        SandboxInstance.builder()
            .id(id)
            .name(name)
            .civiformVersion(version)
            .status(SandboxStatus.PROVISIONING)
            .url(sandboxUrl)
            .adminEmail(adminEmail)
            .notes(notes)
            .pin(pin)
            .createdAt(now)
            .expiresAt(now.plusSeconds(30L * 24 * 3600))
            .build();

    return repository
        .save(instance)
        .thenComposeAsync(
            saved -> {
              // Kick off async provisioning — returns immediately with PROVISIONING status
              CompletableFuture.runAsync(() -> provisionAsync(saved), provisioner);
              return CompletableFuture.completedFuture(saved);
            });
  }

  @Override
  public CompletionStage<Boolean> deleteSandbox(String id) {
    return repository
        .findById(id)
        .thenComposeAsync(
            maybeInstance -> {
              if (maybeInstance.isEmpty()) {
                return CompletableFuture.completedFuture(false);
              }
              return CompletableFuture.supplyAsync(
                  () -> {
                    teardownAsync(maybeInstance.get());
                    return true;
                  },
                  provisioner);
            });
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin) {
    return repository
        .findById(sandboxId)
        .thenApply(
            maybeInstance ->
                maybeInstance.filter(
                    s -> s.getPin() != null && constantTimeEquals(s.getPin(), pin)));
  }

  @Override
  public CompletionStage<Optional<SandboxInstance>> extendSandbox(String id, int days) {
    return repository
        .findById(id)
        .thenComposeAsync(
            maybeInstance -> {
              if (maybeInstance.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
              SandboxInstance updated =
                  SandboxInstance.builder()
                      .id(maybeInstance.get().getId())
                      .name(maybeInstance.get().getName())
                      .civiformVersion(maybeInstance.get().getCiviformVersion())
                      .status(maybeInstance.get().getStatus())
                      .url(maybeInstance.get().getUrl())
                      .adminEmail(maybeInstance.get().getAdminEmail())
                      .notes(maybeInstance.get().getNotes())
                      .pin(maybeInstance.get().getPin())
                      .containerID(maybeInstance.get().getContainerID())
                      .hostPort(maybeInstance.get().getHostPort())
                      .createdAt(maybeInstance.get().getCreatedAt())
                      .expiresAt(
                          maybeInstance.get().getExpiresAt().plusSeconds((long) days * 24 * 3600))
                      .build();
              return repository.save(updated).thenApply(Optional::of);
            });
  }

  // ── Provisioning ────────────────────────────────────────────────────────────

  /**
   * Runs asynchronously after {@link #createSandbox} returns PROVISIONING status.
   *
   * <ol>
   *   <li>Create per-sandbox Postgres schema + user on shared sandbox RDS
   *   <li>Store Secrets Manager secrets (postgres password, app secret key)
   *   <li>Register ECS task definition + run task
   *   <li>Wait for task private IP
   *   <li>Create per-sandbox ALB target group + register task IP
   *   <li>Create ALB listener rule: host-header {slug}.sandbox.civiform.dev → target group
   *   <li>Mark sandbox RUNNING
   * </ol>
   */
  private void provisionAsync(SandboxInstance instance) {
    String id = instance.getId();
    String slug = toSlug(instance.getName());
    logger.info("[{}] Provisioning ECS Fargate sandbox '{}' at {}", id, instance.getName(), instance.getUrl());

    try {
      // Step 1: Create Postgres schema + user
      RdsCredentials masterCreds = readMasterCredentials();
      String dbPassword = generateSecret(24);
      String dbUser = "sandbox_" + id.replace("-", "_");
      createDatabaseSchema(masterCreds, dbUser, dbPassword);
      logger.info("[{}] Postgres schema created: {}", id, dbUser);

      // Step 2: Store Secrets Manager secrets
      String appSecret = generateSecret(32);
      storeSecret("civiform-sandbox_" + id + "_postgres_password", dbPassword);
      storeSecret("civiform-sandbox_" + id + "_app_secret_key", appSecret);
      logger.info("[{}] Secrets stored", id);

      // Step 3: Register task definition + run task
      String taskDefArn = registerTaskDefinition(instance, id, slug, dbUser, dbPassword, appSecret, masterCreds);
      String taskArn = runEcsTask(taskDefArn, id);
      logger.info("[{}] ECS task started: {}", id, taskArn);

      // Step 4: Wait for private IP
      String privateIp = waitForTaskIp(taskArn);
      logger.info("[{}] Task IP: {}", id, privateIp);

      // Step 5: Create per-sandbox target group + register task IP
      String targetGroupArn = createTargetGroup(id);
      registerInTargetGroup(targetGroupArn, privateIp);
      logger.info("[{}] Registered in target group {}", id, targetGroupArn);

      // Step 6: Create ALB listener rule routing slug.sandbox.civiform.dev → this TG
      String ruleArn = createListenerRule(id, slug, targetGroupArn);
      logger.info("[{}] Listener rule created: {} → {}", id, slug + "." + config.getString("sandbox.domain"), ruleArn);

      // Step 7: Mark RUNNING
      updateStatus(id, SandboxStatus.RUNNING, taskArn, targetGroupArn, ruleArn);
      logger.info("[{}] Sandbox RUNNING at {}", id, instance.getUrl());

    } catch (Exception e) {
      logger.error("[{}] Provisioning failed: {}", id, e.getMessage(), e);
      updateStatus(id, SandboxStatus.FAILED, null, null, null);
    }
  }

  // ── ECS ─────────────────────────────────────────────────────────────────────

  private String registerTaskDefinition(
      SandboxInstance instance,
      String id,
      String slug,
      String dbUser,
      String dbPassword,
      String appSecret,
      RdsCredentials masterCreds) {

    String rdsHost = config.getString("sandbox.rds.host");
    int rdsPort = config.getInt("sandbox.rds.port");
    String rdsDb = config.getString("sandbox.rds.dbname");
    String image = config.getString("sandbox.civiform_image");
    String logGroup = config.getString("sandbox.ecs.log_group");
    String region = config.getString("sandbox.aws.region");
    int cpu = config.getInt("sandbox.ecs.task_cpu");
    int memory = config.getInt("sandbox.ecs.task_memory");
    String execRoleArn = config.getString("sandbox.ecs.execution_role");
    String taskRoleArn = config.getString("sandbox.ecs.task_role");
    String sandboxUrl = instance.getUrl(); // already computed as https://{slug}.sandbox.civiform.dev

    String jdbcUrl = String.format(
        "jdbc:postgresql://%s:%d/%s?currentSchema=%s",
        rdsHost, rdsPort, rdsDb, dbUser);

    ContainerDefinition container =
        ContainerDefinition.builder()
            .name("civiform")
            .image(image)
            .essential(true)
            .portMappings(PortMapping.builder().containerPort(9000).build())
            .environment(
                envVar("DB_JDBC_STRING", jdbcUrl),
                envVar("DB_USERNAME", dbUser),
                envVar("DB_PASSWORD", dbPassword),
                envVar("SECRET_KEY", appSecret),
                // FAKE_IDP — no real OIDC secrets needed for MVP
                envVar("CIVIFORM_APPLICANT_IDP", "generic-oidc"),
                envVar("STAGING_DISABLE_DEMO_MODE_LOGINS", "false"),
                envVar("APPLICANT_OIDC_CLIENT_ID", "generic-fake-oidc-client"),
                envVar("APPLICANT_OIDC_CLIENT_SECRET", "bar"),
                envVar("APPLICANT_OIDC_DISCOVERY_URI",
                    "https://dev-oidc.sandbox.civiform.dev/.well-known/openid-configuration"),
                envVar("IDCS_CLIENT_ID", "idcs-fake-oidc-client"),
                envVar("IDCS_SECRET", "idcs-fake-oidc-secret"),
                envVar("IDCS_DISCOVERY_URI",
                    "https://dev-oidc.sandbox.civiform.dev/.well-known/openid-configuration"),
                // City branding — shows city name in CiviForm header
                envVar("WHITELABEL_CIVIC_ENTITY_SHORT_NAME", instance.getName()),
                envVar("WHITELABEL_CIVIC_ENTITY_LONG_NAME", instance.getName()),
                // Per-sandbox URL — each sandbox has its own subdomain
                envVar("BASE_URL", sandboxUrl),
                envVar("STAGING_HOSTNAME", sandboxUrl.replace("https://", "")),
                envVar("PORT", "9000"))
            .logConfiguration(
                LogConfiguration.builder()
                    .logDriver(LogDriver.AWSLOGS)
                    .options(java.util.Map.of(
                        "awslogs-group", logGroup,
                        "awslogs-region", region,
                        "awslogs-stream-prefix", "sandbox-" + id))
                    .build())
            .build();

    RegisterTaskDefinitionResponse response =
        ecs().registerTaskDefinition(
            RegisterTaskDefinitionRequest.builder()
                .family("civiform-sandbox-" + id)
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(
                    software.amazon.awssdk.services.ecs.model.Compatibility.FARGATE)
                .cpu(String.valueOf(cpu))
                .memory(String.valueOf(memory))
                .executionRoleArn(execRoleArn)
                .taskRoleArn(taskRoleArn)
                .containerDefinitions(container)
                .build());

    return response.taskDefinition().taskDefinitionArn();
  }

  private String runEcsTask(String taskDefArn, String sandboxId) {
    String cluster = config.getString("sandbox.ecs.cluster");
    List<String> subnets = config.getStringList("sandbox.ecs.subnets");
    String securityGroup = config.getString("sandbox.ecs.security_group");

    RunTaskResponse response =
        ecs().runTask(
            RunTaskRequest.builder()
                .cluster(cluster)
                .taskDefinition(taskDefArn)
                .launchType(LaunchType.FARGATE)
                .networkConfiguration(
                    NetworkConfiguration.builder()
                        .awsvpcConfiguration(
                            AwsVpcConfiguration.builder()
                                .subnets(subnets)
                                .securityGroups(securityGroup)
                                .assignPublicIp(AssignPublicIp.DISABLED)
                                .build())
                        .build())
                .startedBy("civiform-sandbox-builder")
                .tags(
                    software.amazon.awssdk.services.ecs.model.Tag.builder()
                        .key("SandboxId").value(sandboxId).build())
                .build());

    if (response.failures() != null && !response.failures().isEmpty()) {
      throw new RuntimeException("ECS RunTask failed: " + response.failures().get(0).reason());
    }

    return response.tasks().get(0).taskArn();
  }

  /** Polls until the Fargate task has a private IP. Typically 15–30 seconds. */
  private String waitForTaskIp(String taskArn) throws InterruptedException {
    String cluster = config.getString("sandbox.ecs.cluster");
    for (int i = 0; i < 40; i++) {
      Thread.sleep(5000);
      var describeResp = ecs().describeTasks(b -> b.cluster(cluster).tasks(taskArn));
      if (describeResp.tasks().isEmpty()) continue;
      Task task = describeResp.tasks().get(0);
      if (task.attachments() != null) {
        for (var attachment : task.attachments()) {
          if ("ElasticNetworkInterface".equals(attachment.type())) {
            for (var detail : attachment.details()) {
              if ("privateIPv4Address".equals(detail.name())) {
                return detail.value();
              }
            }
          }
        }
      }
    }
    throw new RuntimeException("Timed out waiting for ECS task private IP");
  }

  // ── ALB: Per-sandbox target group + listener rule ─────────────────────────

  /**
   * Creates a dedicated target group for this sandbox. Named {@code civiform-sb-{id}} (max 32 chars).
   *
   * @return the ARN of the new target group
   */
  private String createTargetGroup(String id) {
    String vpcId = config.getString("sandbox.vpc_id"); // needed for TG creation
    // TG name max 32 chars: "civiform-sb-" (12) + 8-char sandbox id suffix
    String tgName = "civiform-sb-" + id.replace("sb-", "");

    CreateTargetGroupResponse resp = elb().createTargetGroup(
        CreateTargetGroupRequest.builder()
            .name(tgName)
            .protocol(Protocol.HTTP)
            .port(9000)
            .vpcId(vpcId)
            .targetType(TargetType.IP)
            .ipAddressType(IpAddressType.IPV4)
            .healthCheckPath("/health")
            .healthCheckProtocol(Protocol.HTTP)
            .matcher(Matcher.builder().httpCode("200").build())
            .healthyThresholdCount(2)
            .unhealthyThresholdCount(3)
            .healthCheckIntervalSeconds(15)
            .build());

    return resp.targetGroups().get(0).targetGroupArn();
  }

  /**
   * Registers the ECS task's private IP in the sandbox's dedicated target group.
   */
  private void registerInTargetGroup(String targetGroupArn, String privateIp) {
    elb().registerTargets(
        RegisterTargetsRequest.builder()
            .targetGroupArn(targetGroupArn)
            .targets(TargetDescription.builder().id(privateIp).port(9000).build())
            .build());
  }

  /**
   * Creates an ALB listener rule on the shared HTTPS listener that routes
   * {@code {slug}.sandbox.civiform.dev} to this sandbox's target group.
   *
   * <p>Priority is assigned by scanning existing rules and using the next available slot
   * starting from {@link #LISTENER_RULE_PRIORITY_BASE}.
   *
   * @return the ARN of the created listener rule
   */
  private String createListenerRule(String id, String slug, String targetGroupArn) {
    String listenerArn = config.getString("sandbox.alb.listener_arn");
    String domain = config.getString("sandbox.domain");
    String hostHeader = slug + "." + domain;

    int priority = nextAvailableRulePriority(listenerArn);

    var response = elb().createRule(
        CreateRuleRequest.builder()
            .listenerArn(listenerArn)
            .priority(priority)
            .conditions(
                Condition.builder()
                    .field("host-header")
                    .hostHeaderConfig(
                        HostHeaderConditionConfig.builder()
                            .values(hostHeader)
                            .build())
                    .build())
            .actions(
                Action.builder()
                    .type(ActionTypeEnum.FORWARD)
                    .forwardConfig(
                        ForwardActionConfig.builder()
                            .targetGroups(
                                TargetGroupTuple.builder()
                                    .targetGroupArn(targetGroupArn)
                                    .build())
                            .build())
                    .build())
            .tags(
                software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag.builder()
                    .key("SandboxId").value(id).build())
            .build());

    return response.rules().get(0).ruleArn();
  }

  /** Returns the next unused listener rule priority >= {@link #LISTENER_RULE_PRIORITY_BASE}. */
  private int nextAvailableRulePriority(String listenerArn) {
    var existingRules = elb().describeRules(
        DescribeRulesRequest.builder().listenerArn(listenerArn).build()).rules();

    java.util.Set<Integer> usedPriorities = new java.util.HashSet<>();
    for (Rule rule : existingRules) {
      try {
        if (!"default".equals(rule.priority())) {
          usedPriorities.add(Integer.parseInt(rule.priority()));
        }
      } catch (NumberFormatException ignored) {
      }
    }

    int priority = LISTENER_RULE_PRIORITY_BASE;
    while (usedPriorities.contains(priority)) {
      priority++;
    }
    return priority;
  }

  // ── Teardown ─────────────────────────────────────────────────────────────────

  private void teardownAsync(SandboxInstance instance) {
    String id = instance.getId();
    logger.info("[{}] Tearing down sandbox '{}'", id, instance.getName());
    try {
      // Stop ECS task
      if (instance.getContainerID() != null) {
        ecs().stopTask(StopTaskRequest.builder()
            .cluster(config.getString("sandbox.ecs.cluster"))
            .task(instance.getContainerID())
            .reason("Sandbox deleted")
            .build());
        logger.info("[{}] ECS task stopped", id);
      }

      // Delete ALB listener rule
      if (instance.getListenerRuleArn() != null) {
        elb().deleteRule(DeleteRuleRequest.builder()
            .ruleArn(instance.getListenerRuleArn())
            .build());
        logger.info("[{}] Listener rule deleted", id);
      }

      // Deregister from target group and delete it
      if (instance.getTargetGroupArn() != null) {
        try {
          var healthResp = elb().describeTargetHealth(
              DescribeTargetHealthRequest.builder()
                  .targetGroupArn(instance.getTargetGroupArn()).build());
          if (!healthResp.targetHealthDescriptions().isEmpty()) {
            elb().deregisterTargets(DeregisterTargetsRequest.builder()
                .targetGroupArn(instance.getTargetGroupArn())
                .targets(healthResp.targetHealthDescriptions().stream()
                    .map(t -> TargetDescription.builder()
                        .id(t.target().id()).port(t.target().port()).build())
                    .toList())
                .build());
          }
        } catch (Exception e) {
          logger.warn("[{}] Could not deregister from TG: {}", id, e.getMessage());
        }
        elb().deleteTargetGroup(DeleteTargetGroupRequest.builder()
            .targetGroupArn(instance.getTargetGroupArn())
            .build());
        logger.info("[{}] Target group deleted", id);
      }

      // Drop Postgres schema
      RdsCredentials masterCreds = readMasterCredentials();
      String dbUser = "sandbox_" + id.replace("-", "_");
      dropDatabaseSchema(masterCreds, dbUser);
      logger.info("[{}] Postgres schema dropped", id);

      // Delete Secrets Manager secrets
      deleteSecret("civiform-sandbox_" + id + "_postgres_password");
      deleteSecret("civiform-sandbox_" + id + "_app_secret_key");
      logger.info("[{}] Secrets deleted", id);

      updateStatus(id, SandboxStatus.DESTROYED, null, null, null);
      logger.info("[{}] Teardown complete", id);

    } catch (Exception e) {
      logger.error("[{}] Teardown failed: {}", id, e.getMessage(), e);
      updateStatus(id, SandboxStatus.FAILED, null, null, null);
    }
  }

  // ── RDS Schema Management ────────────────────────────────────────────────────

  private void createDatabaseSchema(RdsCredentials creds, String schemaUser, String password) {
    try (Connection conn = DriverManager.getConnection(
            "jdbc:postgresql://" + creds.host + ":" + creds.port + "/" + creds.dbname,
            creds.username, creds.password);
        Statement stmt = conn.createStatement()) {
      stmt.execute(String.format(
          "CREATE USER %s WITH PASSWORD '%s'", schemaUser, password.replace("'", "''")));
      stmt.execute(String.format(
          "CREATE SCHEMA %s AUTHORIZATION %s", schemaUser, schemaUser));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create Postgres schema for " + schemaUser, e);
    }
  }

  private void dropDatabaseSchema(RdsCredentials creds, String schemaUser) {
    try (Connection conn = DriverManager.getConnection(
            "jdbc:postgresql://" + creds.host + ":" + creds.port + "/" + creds.dbname,
            creds.username, creds.password);
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP SCHEMA IF EXISTS " + schemaUser + " CASCADE");
      stmt.execute("DROP USER IF EXISTS " + schemaUser);
    } catch (Exception e) {
      logger.warn("Failed to drop Postgres schema {}: {}", schemaUser, e.getMessage());
    }
  }

  // ── Secrets Manager ──────────────────────────────────────────────────────────

  private void storeSecret(String name, String value) {
    secrets().createSecret(CreateSecretRequest.builder().name(name).secretString(value).build());
  }

  private void deleteSecret(String name) {
    try {
      secrets().deleteSecret(DeleteSecretRequest.builder()
          .secretId(name).forceDeleteWithoutRecovery(true).build());
    } catch (Exception e) {
      logger.warn("Could not delete secret {}: {}", name, e.getMessage());
    }
  }

  private RdsCredentials readMasterCredentials() {
    String secretArn = config.getString("sandbox.rds.master_secret");
    String json = secrets().getSecretValue(
        GetSecretValueRequest.builder().secretId(secretArn).build()).secretString();
    return RdsCredentials.fromJson(json);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
   * Converts a city name to a URL-safe slug.
   *
   * <p>Examples:
   * <ul>
   *   <li>"Burlington, VT" → "burlington-vt"
   *   <li>"San Francisco, CA" → "san-francisco-ca"
   *   <li>"St. Louis, MO" → "st-louis-mo"
   * </ul>
   */
  static String toSlug(String cityName) {
    // Normalize accents and decompose (é → e + combining accent)
    String normalized = Normalizer.normalize(cityName, Normalizer.Form.NFD);
    return normalized
        .toLowerCase()
        .replaceAll("[^a-z0-9\\s-]", "") // strip non-alphanumeric except spaces/hyphens
        .trim()
        .replaceAll("\\s+", "-")         // spaces → hyphens
        .replaceAll("-+", "-");           // collapse consecutive hyphens
  }

  private static String generatePin() {
    return String.format("%06d", new SecureRandom().nextInt(1_000_000));
  }

  private static String generateSecret(int length) {
    SecureRandom rng = new SecureRandom();
    byte[] bytes = new byte[length];
    rng.nextBytes(bytes);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }

  private static KeyValuePair envVar(String name, String value) {
    return KeyValuePair.builder().name(name).value(value).build();
  }

  private void updateStatus(
      String id, SandboxStatus status, String taskArn, String targetGroupArn, String ruleArn) {
    repository.findById(id).thenCompose(maybeInstance -> {
      if (maybeInstance.isEmpty()) return CompletableFuture.completedFuture(null);
      SandboxInstance updated = SandboxInstance.builder()
          .id(maybeInstance.get().getId())
          .name(maybeInstance.get().getName())
          .civiformVersion(maybeInstance.get().getCiviformVersion())
          .status(status)
          .url(maybeInstance.get().getUrl())
          .adminEmail(maybeInstance.get().getAdminEmail())
          .notes(maybeInstance.get().getNotes())
          .pin(maybeInstance.get().getPin())
          .containerID(taskArn != null ? taskArn : maybeInstance.get().getContainerID())
          .targetGroupArn(targetGroupArn != null ? targetGroupArn : maybeInstance.get().getTargetGroupArn())
          .listenerRuleArn(ruleArn != null ? ruleArn : maybeInstance.get().getListenerRuleArn())
          .hostPort(maybeInstance.get().getHostPort())
          .createdAt(maybeInstance.get().getCreatedAt())
          .expiresAt(maybeInstance.get().getExpiresAt())
          .build();
      return repository.save(updated);
    }).toCompletableFuture().join();
  }

  // ── Lazy AWS client accessors (overridable in tests) ─────────────────────────

  protected EcsClient ecs() {
    if (ecsClient == null) {
      synchronized (this) {
        if (ecsClient == null) {
          ecsClient = EcsClient.builder()
              .region(Region.of(config.getString("sandbox.aws.region"))).build();
        }
      }
    }
    return ecsClient;
  }

  protected ElasticLoadBalancingV2Client elb() {
    if (elbClient == null) {
      synchronized (this) {
        if (elbClient == null) {
          elbClient = ElasticLoadBalancingV2Client.builder()
              .region(Region.of(config.getString("sandbox.aws.region"))).build();
        }
      }
    }
    return elbClient;
  }

  protected SecretsManagerClient secrets() {
    if (secretsClient == null) {
      synchronized (this) {
        if (secretsClient == null) {
          secretsClient = SecretsManagerClient.builder()
              .region(Region.of(config.getString("sandbox.aws.region"))).build();
        }
      }
    }
    return secretsClient;
  }

  // ── Inner types ───────────────────────────────────────────────────────────────

  static class RdsCredentials {
    final String host, username, password, dbname;
    final int port;

    RdsCredentials(String host, int port, String username, String password, String dbname) {
      this.host = host;
      this.port = port;
      this.username = username;
      this.password = password;
      this.dbname = dbname;
    }

    static RdsCredentials fromJson(String json) {
      return new RdsCredentials(
          extractJsonString(json, "host"),
          Integer.parseInt(extractJsonString(json, "port")),
          extractJsonString(json, "username"),
          extractJsonString(json, "password"),
          extractJsonString(json, "dbname"));
    }

    private static String extractJsonString(String json, String key) {
      String search = "\"" + key + "\":";
      int idx = json.indexOf(search);
      if (idx == -1) throw new RuntimeException("Key not found in secret JSON: " + key);
      int start = idx + search.length();
      if (json.charAt(start) == '"') {
        int end = json.indexOf('"', start + 1);
        return json.substring(start + 1, end);
      } else {
        int end = json.indexOf(',', start);
        if (end == -1) end = json.indexOf('}', start);
        return json.substring(start, end).trim();
      }
    }
  }
}
