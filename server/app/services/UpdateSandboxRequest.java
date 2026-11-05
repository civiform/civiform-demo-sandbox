package services;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * Immutable value object carrying all editable settings for {@link SandboxService#updateSandbox}.
 */
@Data
@Builder
public class UpdateSandboxRequest {

  /** Human-readable city / civic entity name (e.g. "Burlington, VT"). */
  private final String cityName;

  /**
   * Google Analytics measurement ID (e.g. "G-ABC123XYZ"). Optional.
   * Null if GA is not configured.
   */
  private final String googleAnalyticsId;

  /** Updated expiration timestamp for the sandbox. */
  private final Instant expiresAt;

  /** Admin notification email for the sandbox. */
  private final String adminEmail;

  /** CiviForm image tag / version (e.g. "latest" or "v2.22.0"). */
  private final String civiformVersion;
}
