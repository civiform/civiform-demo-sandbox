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
  private String cityName;

  /**
   * URL subdomain slug chosen by the sales rep (e.g. "burlington-vt").
   * Full URL = {@code https://{subdomain}.sandbox.civiform.dev}.
   */
  private String subdomain;

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

  /** 6-digit PIN gate code — set by the sales rep at creation time. */
  private String pin;

  /**
   * Random per-sandbox access token used as the cookie value.
   * Prevents cookie forgery: knowing the sandbox ID alone is not enough.
   * Generated at creation time via {@code UUID.randomUUID()}.
   */
  private String accessToken;

  /** Docker container ID or ECS task ARN — set once provisioning starts. */
  private String containerId;

  /** Host port bound to CiviForm's internal 9000 (Sprint 1 Docker only). */
  private int hostPort;

  /** Postgres database name for this sandbox (e.g. "sandbox_sb_a1b2c3d4"). */
  private String databaseName;

  /**
   * ARN of the per-sandbox ALB target group (Sprint 2 ECS Fargate only).
   * Created at provision time, deleted at teardown. Null for Docker sandboxes.
   */
  private String targetGroupArn;

  /**
   * ARN of the per-sandbox ALB listener rule routing
   * {slug}.sandbox.civiform.dev → this sandbox's target group (Sprint 2 only).
   * Null for Docker sandboxes.
   */
  private String listenerRuleArn;

  /**
   * Priority of this sandbox's ALB listener rule, allocated atomically from the
   * {@code sandbox_listener_priority_seq} Postgres sequence.
   *
   * <p>Boxed rather than primitive on purpose: {@code null} means "this sandbox has no load
   * balancer rule", which is the normal state for Docker sandboxes. A primitive {@code int} would
   * render that as 0, which is indistinguishable from a real value and is not a legal ALB priority.
   */
  private Integer listenerPriority;

  /**
   * Google Analytics measurement ID (e.g. "G-ABC123XYZ"). Optional.
   * Injected as GOOGLE_ANALYTICS_ID env var into the sandbox container.
   * Null if GA not configured.
   */
  private String googleAnalyticsId;

  /**
   * Full GA property console deep-link URL for the "View in GA" button. Optional.
   * e.g. "https://analytics.google.com/analytics/web/#/a12345p678/reports/intelligenthome"
   * Null if rep has not set it; falls back to GA home in the UI.
   */
  private String googleAnalyticsUrl;

  private Instant createdAt;
  private Instant expiresAt;

  /**
   * Timestamp when the sandbox was soft-deleted (status = DELETED).
   * Null for all other statuses.
   */
  private Instant deletedAt;
}

