package models;

import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Instant;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Records a {@link durablejobs.DurableJob} execution in the {@code persisted_durable_jobs} table.
 *
 * <p>Modeled on CiviForm's {@code PersistedDurableJobModel} (adapted from Ebean to a plain JDBC
 * model). Each record has a {@code jobName} that identifies which job runs when its {@code
 * executionTime} arrives. When a job fails, the error is appended to {@code errorMessage} and
 * {@code remainingAttempts} is decremented.
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
public class PersistedDurableJobModel {

  public static final int DEFAULT_REMAINING_ATTEMPTS = 3;

  private Long id;
  private String jobName;
  private JobType jobType;
  private Instant executionTime;
  private Instant successTime;
  private Instant createTime;
  @Builder.Default private int remainingAttempts = DEFAULT_REMAINING_ATTEMPTS;
  private String errorMessage;

  /** Creates a new unpersisted job record with 3 remaining attempts. */
  public PersistedDurableJobModel(String jobName, JobType jobType, Instant executionTime) {
    this.jobName = checkNotNull(jobName);
    this.jobType = checkNotNull(jobType);
    this.executionTime = checkNotNull(executionTime);
    this.remainingAttempts = DEFAULT_REMAINING_ATTEMPTS;
  }

  /** True when the job never succeeded and has exhausted all execution attempts. */
  public boolean hasFailedWithNoRemainingAttempts() {
    return successTime == null && remainingAttempts == 0;
  }

  public Optional<Instant> getSuccessTime() {
    return Optional.ofNullable(successTime);
  }

  public PersistedDurableJobModel setSuccessTime(Instant time) {
    this.successTime = time;
    return this;
  }

  public PersistedDurableJobModel setExecutionTime(Instant executionTime) {
    this.executionTime = checkNotNull(executionTime);
    return this;
  }

  public PersistedDurableJobModel decrementRemainingAttempts() {
    this.remainingAttempts--;
    return this;
  }

  public Optional<String> getErrorMessage() {
    return Optional.ofNullable(errorMessage);
  }

  /** Appends an error message separated by {@code END_ERROR}, matching CiviForm's format. */
  public PersistedDurableJobModel appendErrorMessage(String newMessage) {
    this.errorMessage =
        this.errorMessage == null
            ? newMessage
            : String.format("%s\nEND_ERROR\n\n%s", this.errorMessage, newMessage);
    return this;
  }
}
