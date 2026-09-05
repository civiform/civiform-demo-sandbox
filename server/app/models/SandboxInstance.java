package models;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/** Represents a single CiviForm demo sandbox instance. */
@Data
@Builder(toBuilder = true)
public class SandboxInstance {
  /** Unique sandbox ID (e.g. "sb-a1b2c3d4"). */
  private String id;

  /** Human-readable city name (e.g. "Burlington, VT"). */
  private String name;

  /** CiviForm image tag (e.g. "latest" or "v2.22.0"). */
  private String civiformVersion;

  /** Current lifecycle status. */
  private SandboxStatus status;

  /**
   * Sandbox URL — per-sandbox subdomain under wildcard cert.
   * e.g. "https://burlington-vt.sandbox.civiform.dev"
   */
  private String url;

  /** Email of the sales rep / admin who created this sandbox. */
  private String adminEmail;

  /** Optional notes from the creator. */
  private String notes;

  /** 6-digit PIN gate code — generated at creation, shown on detail page. */
  private String pin;

  /** ECS task ARN set once the Fargate task is launched (null while PROVISIONING). */
  private String containerID;

  /** Host port (Sprint 1 Docker only — unused in ECS Fargate path). */
  private int hostPort;

  /**
   * ARN of the per-sandbox ALB target group.
   * Created at provision time, deleted at teardown.
   * Null for Docker-socket (Sprint 1) sandboxes.
   */
  private String targetGroupArn;

  /**
   * ARN of the per-sandbox ALB listener rule (host-header → target group).
   * Created at provision time, deleted at teardown.
   * Null for Docker-socket (Sprint 1) sandboxes.
   */
  private String listenerRuleArn;

  private Instant createdAt;
  private Instant expiresAt;
}


