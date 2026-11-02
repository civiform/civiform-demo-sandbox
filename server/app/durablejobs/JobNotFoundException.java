package durablejobs;

/** Thrown when a persisted durable job references an unregistered {@link DurableJobName}. */
public final class JobNotFoundException extends Exception {

  public JobNotFoundException(String message) {
    super(message);
  }
}
