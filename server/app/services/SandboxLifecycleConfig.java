package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Single source of truth for sandbox lifecycle settings shared by the scheduled teardown job and
 * the dashboard UI, so both always agree on when an expired sandbox will be deleted.
 *
 * <p>Values are validated once at construction. Invalid configuration fails application startup
 * rather than being silently coerced — e.g. a negative grace period must never turn into
 * "delete immediately after expiry".
 */
@Singleton
public final class SandboxLifecycleConfig {

  /** Config path for the post-expiration grace period, overridable via AUTODELETION_GRACE_DAYS. */
  public static final String AUTODELETION_GRACE_DAYS_PATH = "sandbox.autodeletionGraceDays";

  /** Grace period used when {@link #AUTODELETION_GRACE_DAYS_PATH} is not configured. */
  public static final long DEFAULT_AUTODELETION_GRACE_DAYS = 7L;

  private final long autodeletionGraceDays;

  /**
   * Reads and validates lifecycle settings from {@code config}.
   *
   * @throws ConfigException.BadValue if {@code sandbox.autodeletionGraceDays} is negative
   * @throws ConfigException.WrongType if it is not a whole number
   */
  @Inject
  public SandboxLifecycleConfig(Config config) {
    checkNotNull(config);
    long graceDays =
        config.hasPath(AUTODELETION_GRACE_DAYS_PATH)
            ? config.getLong(AUTODELETION_GRACE_DAYS_PATH)
            : DEFAULT_AUTODELETION_GRACE_DAYS;
    if (graceDays < 0) {
      throw new ConfigException.BadValue(
          AUTODELETION_GRACE_DAYS_PATH,
          "must be >= 0 (AUTODELETION_GRACE_DAYS), got " + graceDays);
    }
    this.autodeletionGraceDays = graceDays;
  }

  /** Builds a config with the given grace period, for tests. */
  @VisibleForTesting
  public static SandboxLifecycleConfig withGraceDays(long graceDays) {
    return new SandboxLifecycleConfig(
        ConfigFactory.parseMap(Map.of(AUTODELETION_GRACE_DAYS_PATH, graceDays)));
  }

  /** Days after a sandbox's expiration before it is automatically deleted. Always {@code >= 0}. */
  public long getAutodeletionGraceDays() {
    return autodeletionGraceDays;
  }
}
