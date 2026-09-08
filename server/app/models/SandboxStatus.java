package models;

public enum SandboxStatus {
  PROVISIONING,
  RUNNING,
  STOPPED,
  FAILED,
  DESTROYED,
  /** Soft-delete tombstone: sandbox is gone from AWS but kept in the list with "Deleted [date]". */
  DELETED
}
