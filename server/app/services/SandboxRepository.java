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

  /** Persists a new sandbox row (called before async container launch). */
  public void save(SandboxInstance instance) {
    db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO sandbox_instances "
              + "(id, city_name, civiform_version, status, url, admin_email, notes, "
              + " pin, host_port, schema_name, created_at, expires_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
        ps.setString(1, instance.getId());
        ps.setString(2, instance.getCityName());
        ps.setString(3, instance.getCiviformVersion());
        ps.setString(4, instance.getStatus().name());
        ps.setString(5, instance.getUrl());
        ps.setString(6, instance.getAdminEmail());
        ps.setString(7, instance.getNotes());
        ps.setString(8, instance.getPin());
        ps.setInt(9, instance.getHostPort());
        ps.setString(10, instance.getSchemaName());
        ps.setTimestamp(11, Timestamp.from(instance.getCreatedAt()));
        ps.setTimestamp(12, Timestamp.from(instance.getExpiresAt()));
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

  public boolean delete(String id) {
    return db.withConnection(conn -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "DELETE FROM sandbox_instances WHERE id = ?")) {
        ps.setString(1, id);
        return ps.executeUpdate() > 0;
      }
    });
  }

  private SandboxInstance mapRow(ResultSet rs) throws java.sql.SQLException {
    return SandboxInstance.builder()
        .id(rs.getString("id"))
        .cityName(rs.getString("city_name"))
        .civiformVersion(rs.getString("civiform_version"))
        .status(SandboxStatus.valueOf(rs.getString("status")))
        .url(rs.getString("url"))
        .adminEmail(rs.getString("admin_email"))
        .notes(rs.getString("notes"))
        .pin(rs.getString("pin"))
        .containerId(rs.getString("container_id"))
        .hostPort(rs.getInt("host_port"))
        .schemaName(rs.getString("schema_name"))
        .createdAt(rs.getTimestamp("created_at").toInstant())
        .expiresAt(rs.getTimestamp("expires_at").toInstant())
        .build();
  }
}
