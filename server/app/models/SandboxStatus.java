package models;

public enum SandboxStatus {
  PROVISIONING,
  RUNNING,
  STOPPED,
  FAILED,
  /**
   * Teardown failed after the container was stopped: the per-sandbox database (and its data) may
   * still exist. The row is kept, not deleted, so the drop can be retried; the row is the only
   * record of the database name.
   */
  DELETE_FAILED,
  DESTROYED,
  /** Soft-delete tombstone: sandbox is gone from AWS but kept in the list with "Deleted [date]". */
  DELETED
}
