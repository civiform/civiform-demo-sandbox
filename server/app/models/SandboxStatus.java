package models;

public enum SandboxStatus {
  PROVISIONING,
  RUNNING,
  STOPPED,
  FAILED,
  /** Teardown has been initiated and is running in the background. */
  DELETING,
  /**
   * Teardown failed after the container was stopped: the per-sandbox database (and its data) may
   * still exist. The row is kept, not deleted, so the drop can be retried; the row is the only
   * record of the database name.
   */
  DELETE_FAILED,
  /**
   * Soft-delete tombstone: sandbox infrastructure and database have been destroyed (manually or via
   * scheduled expiration), and the scrubbed row is retained with {@code deleted_at} recorded.
   */
  DELETED
}
