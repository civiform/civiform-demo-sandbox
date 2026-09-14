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
   * Persists a sandbox row. Uses upsert (INSERT ... ON CONFLICT DO UPDATE) so it works
   * for both initial creation and subsequent updates (e.g. extendSandbox, soft-delete).
   */
  public void save(SandboxInstance instance) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO sandbox_instances "
              + "(id, city_name, subdomain, civiform_version, status, url, admin_email, "
              + " pin, container_id, host_port, schema_name, target_group_arn, "
              + " listener_rule_arn, google_analytics_id, google_analytics_url, "
              + " created_at, expires_at, deleted_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
              + "ON CONFLICT (id) DO UPDATE SET "
              + " city_name = EXCLUDED.city_name,"
              + " subdomain = EXCLUDED.subdomain,"
              + " civiform_version = EXCLUDED.civiform_version,"
              + " status = EXCLUDED.status,"
              + " url = EXCLUDED.url,"
              + " admin_email = EXCLUDED.admin_email,"
              + " pin = EXCLUDED.pin,"
              + " container_id = EXCLUDED.container_id,"
              + " host_port = EXCLUDED.host_port,"
              + " schema_name = EXCLUDED.schema_name,"
              + " target_group_arn = EXCLUDED.target_group_arn,"
              + " listener_rule_arn = EXCLUDED.listener_rule_arn,"
              + " google_analytics_id = EXCLUDED.google_analytics_id,"
              + " google_analytics_url = EXCLUDED.google_analytics_url,"
              + " expires_at = EXCLUDED.expires_at,"
              + " deleted_at = EXCLUDED.deleted_at")) {
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
        ps.setString(11, instance.getSchemaName());
        ps.setString(12, instance.getTargetGroupArn());
        ps.setString(13, instance.getListenerRuleArn());
        ps.setString(14, instance.getGoogleAnalyticsId());
        ps.setString(15, instance.getGoogleAnalyticsUrl());
        ps.setTimestamp(16, instance.getCreatedAt() != null ? Timestamp.from(instance.getCreatedAt()) : null);
        ps.setTimestamp(17, instance.getExpiresAt() != null ? Timestamp.from(instance.getExpiresAt()) : null);
        ps.setTimestamp(18, instance.getDeletedAt() != null ? Timestamp.from(instance.getDeletedAt()) : null);
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
        .schemaName(rs.getString("schema_name"))
        .targetGroupArn(rs.getString("target_group_arn"))
        .listenerRuleArn(rs.getString("listener_rule_arn"))
        .googleAnalyticsId(rs.getString("google_analytics_id"))
        .googleAnalyticsUrl(rs.getString("google_analytics_url"))
        .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
        .expiresAt(rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null)
        .deletedAt(rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null)
        .build();
  }
}
