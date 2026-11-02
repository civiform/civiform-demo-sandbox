package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.JobType;
import models.PersistedDurableJobModel;
import play.db.Database;

/**
 * JDBC repository for {@link PersistedDurableJobModel} records in {@code persisted_durable_jobs}.
 *
 * <p>Replicates CiviForm's {@code PersistedDurableJobRepository} using Play's JDBC connection pool
 * and Postgres {@code FOR UPDATE SKIP LOCKED} row locking.
 */
@Singleton
public class PersistedDurableJobRepository {

  private final Database db;
  private final Clock clock;

  @Inject
  public PersistedDurableJobRepository(Database db, Clock clock) {
    this.db = checkNotNull(db);
    this.clock = checkNotNull(clock);
  }

  /**
   * Finds an existing uncompleted recurring job matching {@code jobName} that still has remaining
   * attempts, or empty if none is scheduled.
   */
  public Optional<PersistedDurableJobModel> findScheduledRecurringJob(String jobName) {
    return db.withConnection(
        conn -> {
          return findScheduledRecurringJob(conn, jobName);
        });
  }

  /** Transaction-scoped variant of {@link #findScheduledRecurringJob(String)}. */
  public Optional<PersistedDurableJobModel> findScheduledRecurringJob(
      Connection conn, String jobName) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT * FROM persisted_durable_jobs "
                + "WHERE job_name = ? AND success_time IS NULL AND remaining_attempts > 0 "
                + "LIMIT 1")) {
      ps.setString(1, jobName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
      }
    }
  }

  /**
   * Claims a single recurring job that is ready for execution inside {@code conn}'s open
   * transaction using {@code FOR UPDATE SKIP LOCKED} so concurrent server instances never execute
   * the same job simultaneously.
   */
  public Optional<PersistedDurableJobModel> getRecurringJobForExecution(Connection conn)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT * FROM persisted_durable_jobs "
                + "WHERE job_type = ? "
                + "  AND execution_time <= ? "
                + "  AND remaining_attempts > 0 "
                + "  AND success_time IS NULL "
                + "ORDER BY execution_time ASC "
                + "LIMIT 1 "
                + "FOR UPDATE SKIP LOCKED")) {
      ps.setString(1, JobType.RECURRING.name());
      ps.setTimestamp(2, Timestamp.from(clock.instant()));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
      }
    }
  }

  /** Inserts a new job (setting its generated {@code id}) or updates an existing job row. */
  public PersistedDurableJobModel save(Connection conn, PersistedDurableJobModel job)
      throws SQLException {
    if (job.getId() == null) {
      Instant createTime = job.getCreateTime() != null ? job.getCreateTime() : clock.instant();
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO persisted_durable_jobs "
                  + "(job_name, job_type, execution_time, success_time, create_time, "
                  + " remaining_attempts, error_message) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                  + "RETURNING id, create_time")) {
        ps.setString(1, job.getJobName());
        ps.setString(2, job.getJobType().name());
        ps.setTimestamp(3, Timestamp.from(job.getExecutionTime()));
        ps.setTimestamp(
            4, job.getSuccessTime().map(Timestamp::from).orElse(null));
        ps.setTimestamp(5, Timestamp.from(createTime));
        ps.setInt(6, job.getRemainingAttempts());
        ps.setString(7, job.getErrorMessage().orElse(null));
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            job.setId(rs.getLong("id"));
            Timestamp created = rs.getTimestamp("create_time");
            if (created != null) {
              job.setCreateTime(created.toInstant());
            }
          }
        }
      }
      return job;
    }

    try (PreparedStatement ps =
        conn.prepareStatement(
            "UPDATE persisted_durable_jobs "
                + "SET job_name = ?, job_type = ?, execution_time = ?, success_time = ?, "
                + "    remaining_attempts = ?, error_message = ? "
                + "WHERE id = ?")) {
      ps.setString(1, job.getJobName());
      ps.setString(2, job.getJobType().name());
      ps.setTimestamp(3, Timestamp.from(job.getExecutionTime()));
      ps.setTimestamp(4, job.getSuccessTime().map(Timestamp::from).orElse(null));
      ps.setInt(5, job.getRemainingAttempts());
      ps.setString(6, job.getErrorMessage().orElse(null));
      ps.setLong(7, job.getId());
      ps.executeUpdate();
    }
    return job;
  }

  /** Deletes a job row inside {@code conn}'s open transaction. */
  public boolean delete(Connection conn, long id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("DELETE FROM persisted_durable_jobs WHERE id = ?")) {
      ps.setLong(1, id);
      return ps.executeUpdate() > 0;
    }
  }

  /** Returns all jobs ordered by {@code execution_time} ascending. */
  public ImmutableList<PersistedDurableJobModel> getJobs() {
    return db.withConnection(
        conn -> {
          try (PreparedStatement ps =
                  conn.prepareStatement(
                      "SELECT * FROM persisted_durable_jobs ORDER BY execution_time ASC");
              ResultSet rs = ps.executeQuery()) {
            List<PersistedDurableJobModel> result = new ArrayList<>();
            while (rs.next()) {
              result.add(mapRow(rs));
            }
            return ImmutableList.copyOf(result);
          }
        });
  }

  /** Deletes recurring jobs whose {@code execution_time} is older than six months. */
  public int deleteJobsOlderThanSixMonths() {
    Instant sixMonthsAgo =
        ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusMonths(6).toInstant();
    return db.withConnection(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "DELETE FROM persisted_durable_jobs "
                      + "WHERE job_type = ? "
                      + "  AND execution_time < ?")) {
            ps.setString(1, JobType.RECURRING.name());
            ps.setTimestamp(2, Timestamp.from(sixMonthsAgo));
            return ps.executeUpdate();
          }
        });
  }

  private static PersistedDurableJobModel mapRow(ResultSet rs) throws SQLException {
    Timestamp successTs = rs.getTimestamp("success_time");
    Timestamp createTs = rs.getTimestamp("create_time");
    return PersistedDurableJobModel.builder()
        .id(rs.getLong("id"))
        .jobName(rs.getString("job_name"))
        .jobType(JobType.valueOf(rs.getString("job_type")))
        .executionTime(rs.getTimestamp("execution_time").toInstant())
        .successTime(successTs != null ? successTs.toInstant() : null)
        .createTime(createTs != null ? createTs.toInstant() : null)
        .remainingAttempts(rs.getInt("remaining_attempts"))
        .errorMessage(rs.getString("error_message"))
        .build();
  }
}
