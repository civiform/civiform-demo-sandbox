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

  /** CiviForm image tag (e.g. "latest" or "v2.22.0"). */
  private String civiformVersion;

  /** Current lifecycle status. */
  private SandboxStatus status;

  /** URL prospects use to reach the CiviForm instance. */
  private String url;

  /** Email of the sales rep / admin who created this sandbox. */
  private String adminEmail;

  /** Optional notes from the creator. */
  private String notes;

  /** 6-digit PIN gate code — generated at creation, shown on detail page. */
  private String pin;

  /** Docker container ID set once the container is launched (null while PROVISIONING). */
  private String containerId;

  /** Host port bound to CiviForm's internal 9000 (allocated from sandbox_port_seq). */
  private int hostPort;

  /** Postgres schema name for this sandbox (e.g. "sandbox_sb_a1b2c3d4"). */
  private String schemaName;

  private Instant createdAt;
  private Instant expiresAt;
}

