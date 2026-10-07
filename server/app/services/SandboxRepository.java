package services;

import com.google.common.collect.ImmutableList;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.SandboxInstance;
import models.SandboxStatus;
import play.db.Database;

/**
 * JDBC-backed repository for {@link SandboxInstance} persistence.
 * Uses Play's connection pool (db.default.*) — no ORM layer.
 */
@Singleton
public class SandboxRepository {

  private final Database db;

  @Inject
  public SandboxRepository(Database db) {
    this.db = db;
  }

  /** Allocates the next host port atomically via the Postgres sequence. */
  public int nextPort() {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT nextval('sandbox_port_seq')");
           ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    });
  }

  /**
   * Allocates the next ALB listener rule priority atomically via the Postgres sequence.
   *
   * <p>ALB requires rule priorities to be unique per listener. A sequence is the only allocation
   * strategy here that is safe under concurrency: describing the listener's existing rules and
   * picking the next free number — which is what {@code EcsFargateSandboxService} did — races
   * whenever two sandboxes are provisioned at once, and the loser fails partway through, after its
   * ECS service has already been created.
   *
   * <p>The sequence is declared {@code NO CYCLE}, so exhaustion raises rather than silently
   * reissuing a priority that a live rule already holds.
   */
  public int nextListenerPriority() {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT nextval('sandbox_listener_priority_seq')");
           ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    });
  }

  /** Persists a new sandbox row (called before async container launch). */
  public void save(SandboxInstance instance) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO sandbox_instances "
              + "(id, city_name, subdomain, civiform_version, status, url, admin_email, "
              + " pin, container_id, host_port, database_name, target_group_arn, "
              + " listener_rule_arn, listener_priority, google_analytics_id, "
              + " google_analytics_url, created_at, expires_at, deleted_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        ps.setString(1, instance.getId());
        ps.setString(2, instance.getCityName());
        ps.setString(3, instance.getSubdomain());
        ps.setString(4, instance.getCiviformVersion());
        ps.setString(5, instance.getStatus().name());
        ps.setString(6, instance.getUrl());
        ps.setString(7, instance.getAdminEmail());
        ps.setString(8, instance.getPin());
        ps.setString(9, instance.getContainerId());
        ps.setInt(10, instance.getHostPort());
        ps.setString(11, instance.getDatabaseName());
        ps.setString(12, instance.getTargetGroupArn());
        ps.setString(13, instance.getListenerRuleArn());
        // setObject rather than setInt: the column is nullable and Docker sandboxes
        // legitimately have no listener rule. setInt would coerce null to 0.
        ps.setObject(14, instance.getListenerPriority(), java.sql.Types.INTEGER);
        ps.setString(15, instance.getGoogleAnalyticsId());
        ps.setString(16, instance.getGoogleAnalyticsUrl());
        ps.setTimestamp(17, instance.getCreatedAt() != null ? Timestamp.from(instance.getCreatedAt()) : null);
        ps.setTimestamp(18, instance.getExpiresAt() != null ? Timestamp.from(instance.getExpiresAt()) : null);
        ps.setTimestamp(19, instance.getDeletedAt() != null ? Timestamp.from(instance.getDeletedAt()) : null);
        ps.executeUpdate();
      }
      return null;
    });
  }

  /** Updates the status column for an existing sandbox. */
  public void updateStatus(String id, SandboxStatus status) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances SET status = ? WHERE id = ?")) {
        ps.setString(1, status.name());
        ps.setString(2, id);
        ps.executeUpdate();
      }
      return null;
    });
  }

  /**
   * Transitions any sandboxes left in a transient in-flight state ({@link
   * SandboxStatus#PROVISIONING} or {@link SandboxStatus#DELETING}) to their error equivalents
   * ({@link SandboxStatus#FAILED} and {@link SandboxStatus#DELETE_FAILED}).
   *
   * <p>Called once at service startup: provisioning and teardown run on an in-memory thread pool,
   * so any row still in a pending state when the process boots was interrupted by a restart and
   * will never complete on its own.
   *
   * @return the number of sandbox rows updated ({@code 0} on a clean startup, or {@code > 0} when
   *     interrupted provisioning/teardown operations were marked as failed)
   */
  public int failInterruptedOperations() {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances "
              + "SET status = CASE status "
              + "  WHEN 'PROVISIONING' THEN 'FAILED' "
              + "  WHEN 'DELETING'     THEN 'DELETE_FAILED' "
              + "END "
              + "WHERE status IN ('PROVISIONING', 'DELETING')")) {
        return ps.executeUpdate();
      }
    });
  }

  /** Sets the Docker container ID once the container is launched. */
  public void updateContainerId(String id, String containerId) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances SET container_id = ? WHERE id = ?")) {
        ps.setString(1, containerId);
        ps.setString(2, id);
        ps.executeUpdate();
      }
      return null;
    });
  }

  /**
   * Moves a sandbox's expiry.
   *
   * <p>Exists because {@code save} is a plain INSERT: calling it with an already-persisted
   * instance, as an "update", raises a duplicate key violation on the primary key.
   */
  public boolean updateExpiry(String id, Instant expiresAt) {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances SET expires_at = ? WHERE id = ?")) {
        ps.setTimestamp(1, Timestamp.from(expiresAt));
        ps.setString(2, id);
        return ps.executeUpdate() > 0;
      }
    });
  }

  /**
   * Records the AWS resources a Terraform apply produced.
   *
   * <p>Written as a single statement so a sandbox row never shows a URL without the ARNs needed to
   * tear it down. The priority is allocated and stored before apply; only the resulting ARNs and
   * the URL are set here.
   */
  public void updateTerraformOutputs(
      String id, String url, String targetGroupArn, String listenerRuleArn, String taskDefinitionArn) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances "
              + "SET url = ?, target_group_arn = ?, listener_rule_arn = ?, container_id = ? "
              + "WHERE id = ?")) {
        ps.setString(1, url);
        ps.setString(2, targetGroupArn);
        ps.setString(3, listenerRuleArn);
        // container_id is the runtime's opaque handle for the workload. For Docker that is a
        // container ID; for Terraform the closest equivalent is the task definition ARN, which is
        // what identifies the exact image and configuration this sandbox is pinned to.
        ps.setString(4, taskDefinitionArn);
        ps.setString(5, id);
        ps.executeUpdate();
      }
      return null;
    });
  }

  public Optional<SandboxInstance> findById(String id) {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT * FROM sandbox_instances WHERE id = ?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return Optional.of(mapRow(rs));
          }
          return Optional.empty();
        }
      }
    });
  }

  public ImmutableList<SandboxInstance> findAll() {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT * FROM sandbox_instances ORDER BY created_at DESC");
           ResultSet rs = ps.executeQuery()) {
        List<SandboxInstance> result = new ArrayList<>();
        while (rs.next()) {
          result.add(mapRow(rs));
        }
        return ImmutableList.copyOf(result);
      }
    });
  }

  /**
   * Soft-deletes a sandbox: marks it as DELETED with a timestamp.
   * The row remains in the database so the dashboard can show a tombstone.
   * The container and database are cleaned up by the caller before this is invoked.
   */
  public boolean delete(String id) {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances SET status = 'DELETED', deleted_at = NOW() WHERE id = ?")) {
        ps.setString(1, id);
        return ps.executeUpdate() > 0;
      }
    });
  }

  /**
   * Marks a torn-down sandbox as a {@link SandboxStatus#DELETED} tombstone instead of removing
   * the row. The row stays as the audit record of the demo (including its database name, for
   * orphaned-resource tracking); the PIN and admin email are cleared so the tombstone holds no
   * secrets or personal data.
   */
  public boolean softDelete(String id, Instant deletedAt) {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE sandbox_instances "
              + "SET status = ?, deleted_at = ?, pin = '', admin_email = '' "
              + "WHERE id = ?")) {
        ps.setString(1, SandboxStatus.DELETED.name());
        ps.setTimestamp(2, Timestamp.from(deletedAt));
        ps.setString(3, id);
        return ps.executeUpdate() > 0;
      }
    });
  }

  private SandboxInstance mapRow(ResultSet rs) throws java.sql.SQLException {
    return SandboxInstance.builder()
        .id(rs.getString("id"))
        .cityName(rs.getString("city_name"))
        .subdomain(rs.getString("subdomain"))
        .civiformVersion(rs.getString("civiform_version"))
        .status(SandboxStatus.valueOf(rs.getString("status")))
        .url(rs.getString("url"))
        .adminEmail(rs.getString("admin_email"))
        .pin(rs.getString("pin"))
        .containerId(rs.getString("container_id"))
        .hostPort(rs.getInt("host_port"))
        .databaseName(rs.getString("database_name"))
        .targetGroupArn(rs.getString("target_group_arn"))
        .listenerRuleArn(rs.getString("listener_rule_arn"))
        // getObject, not getInt: getInt maps SQL NULL to 0, which would make a Docker
        // sandbox look like it holds ALB rule priority 0.
        .listenerPriority(rs.getObject("listener_priority", Integer.class))
        .googleAnalyticsId(rs.getString("google_analytics_id"))
        .googleAnalyticsUrl(rs.getString("google_analytics_url"))
        .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
        .expiresAt(rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null)
        .deletedAt(rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null)
        .build();
  }
}
