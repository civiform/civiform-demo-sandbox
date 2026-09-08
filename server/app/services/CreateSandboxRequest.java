package services;

import lombok.Builder;
import lombok.Data;

/**
 * Immutable value object carrying all inputs for {@link SandboxService#createSandbox}.
 *
 * <p>Using a DTO avoids a 7-param method and makes future additions backward-compatible.
 */
@Data
@Builder
public class CreateSandboxRequest {

  /** Human-readable city / civic entity name. e.g. "Burlington, VT". */
  private final String cityName;

  /**
   * URL subdomain slug chosen by the sales rep. e.g. "burlington-vt".
   * The live sandbox URL will be {@code https://{subdomain}.sandbox.civiform.dev}.
   * Must be lowercase alphanumeric + hyphens only.
   */
  private final String subdomain;

  /**
   * 6-digit numeric PIN set by the sales rep. Shared with the prospect to gate access.
   * Stored as plaintext for MVP; hash with bcrypt post-MVP.
   */
  private final String pin;

  /** Email of the sales rep — receives "your sandbox is ready" notification. */
  private final String adminEmail;

  /** How many days until the sandbox expires. Defaults to 30 if not set. */
  @Builder.Default
  private final int expirationDays = 30;

  /**
   * Google Analytics measurement ID. e.g. "G-ABC123XYZ". Optional.
   * Injected as {@code GOOGLE_ANALYTICS_ID} env var into the sandbox container.
   */
  private final String googleAnalyticsId;

  /**
   * Full GA property console URL for the "View in GA" deep link. Optional.
   * e.g. "https://analytics.google.com/analytics/web/#/a12345p678/reports/intelligenthome"
   * Falls back to GA home if blank.
   */
  private final String googleAnalyticsUrl;
}
