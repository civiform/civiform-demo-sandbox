package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import play.db.ConnectionCallable;
import play.db.Database;

/**
 * Unit tests for {@link SandboxRepository}.
 *
 * <p>Strategy: mock Play's {@link Database} interface to intercept the ConnectionCallable
 * and provide a mock {@link Connection}, {@link PreparedStatement}, and {@link ResultSet}.
 * Verifies that the correct SQL strings and parameter bindings are used for each operation.
 */
public class SandboxRepositoryTest {

  private Database db;
  private Connection conn;
  private SandboxRepository repository;

  @Before
  public void setUp() throws Exception {
    db = mock(Database.class);
    conn = mock(Connection.class);
    when(db.withConnection(ArgumentMatchers.<ConnectionCallable<Object>>any()))
        .thenAnswer(
            invocation -> {
              ConnectionCallable<?> callable = invocation.getArgument(0);
              return callable.call(conn);
            });
    repository = new SandboxRepository(db);
  }

  @Test
  public void nextPort_executesSequenceQuery_returnsNextValue() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement("SELECT nextval('sandbox_port_seq')")).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getInt(1)).thenReturn(9001);

    int port = repository.nextPort();

    assertThat(port).isEqualTo(9001);
    verify(ps).executeQuery();
  }

  @Test
  public void nextListenerPriority_executesSequenceQuery_returnsNextValue() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement("SELECT nextval('sandbox_listener_priority_seq')")).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getInt(1)).thenReturn(1005);

    int priority = repository.nextListenerPriority();

    assertThat(priority).isEqualTo(1005);
    verify(ps).executeQuery();
  }

  @Test
  public void save_validInstance_bindsAllFieldsCorrectly() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    Instant now = Instant.now();
    SandboxInstance instance =
        SandboxInstance.builder()
            .id("sb-123")
            .cityName("Test City")
            .subdomain("test-city")
            .civiformVersion("latest")
            .status(SandboxStatus.PROVISIONING)
            .url("https://test.dev")
            .adminEmail("admin@test.com")
            .pin("123456")
            .accessToken("token123")
            .containerId("container456")
            .hostPort(9000)
            .databaseName("db_test")
            .targetGroupArn("arn:tg")
            .listenerRuleArn("arn:rule")
            .listenerPriority(10)
            .googleAnalyticsId("GA-123")
            .googleAnalyticsUrl("https://ga.dev")
            .createdAt(now)
            .expiresAt(now.plus(30, ChronoUnit.DAYS))
            .deletedAt(null)
            .build();

    repository.save(instance);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).startsWith("INSERT INTO sandbox_instances");

    verify(ps).setString(1, "sb-123");
    verify(ps).setString(2, "Test City");
    verify(ps).setString(3, "test-city");
    verify(ps).setString(4, "latest");
    verify(ps).setString(5, "PROVISIONING");
    verify(ps).setString(6, "https://test.dev");
    verify(ps).setString(7, "admin@test.com");
    verify(ps).setString(8, "123456");
    verify(ps).setString(9, "token123");
    verify(ps).setString(10, "container456");
    verify(ps).setInt(11, 9000);
    verify(ps).setString(12, "db_test");
    verify(ps).setString(13, "arn:tg");
    verify(ps).setString(14, "arn:rule");
    verify(ps).setObject(15, 10, java.sql.Types.INTEGER);
    verify(ps).setString(16, "GA-123");
    verify(ps).setString(17, "https://ga.dev");
    verify(ps).setTimestamp(18, Timestamp.from(now));
    verify(ps).setTimestamp(19, Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
    verify(ps).setTimestamp(20, null);
    verify(ps).executeUpdate();
  }

  @Test
  public void save_nullOptionals_bindsNullsCorrectly() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    SandboxInstance instance =
        SandboxInstance.builder()
            .id("sb-456")
            .status(SandboxStatus.RUNNING)
            .listenerPriority(null) // explicitly null
            .build();

    repository.save(instance);

    verify(ps).setObject(15, null, java.sql.Types.INTEGER);
    verify(ps).setTimestamp(18, null);
    verify(ps).setTimestamp(19, null);
    verify(ps).setTimestamp(20, null);
    verify(ps).executeUpdate();
  }

  @Test
  public void updateStatus_existingId_bindsParameters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement("UPDATE sandbox_instances SET status = ? WHERE id = ?"))
        .thenReturn(ps);

    repository.updateStatus("sb-1", SandboxStatus.RUNNING);

    verify(ps).setString(1, "RUNNING");
    verify(ps).setString(2, "sb-1");
    verify(ps).executeUpdate();
  }

  @Test
  public void ensureSchemaInitialized_executesInitPostgresSql() throws Exception {
    Statement statement = mock(Statement.class);
    when(conn.createStatement()).thenReturn(statement);

    repository.ensureSchemaInitialized();

    String expectedSql = Files.readString(Path.of("../init_postgres.sql"), StandardCharsets.UTF_8);
    verify(statement).execute(expectedSql);
    assertThat(expectedSql)
        .contains("CREATE SEQUENCE IF NOT EXISTS sandbox_port_seq")
        .contains("CREATE SEQUENCE IF NOT EXISTS sandbox_listener_priority_seq")
        .contains("CREATE TABLE IF NOT EXISTS sandbox_instances")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS sandbox_instances_subdomain_live_idx");
  }

  @Test
  public void failInterruptedOperations_someRowsMatch_returnsUpdateCount() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(3);

    int count = repository.failInterruptedOperations();

    assertThat(count).isEqualTo(3);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("WHEN 'PROVISIONING' THEN 'FAILED'");
  }

  @Test
  public void failInterruptedOperations_cleanState_returnsZero() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(0);

    int count = repository.failInterruptedOperations();

    assertThat(count).isEqualTo(0);
  }

  @Test
  public void updateContainerId_existingId_bindsParameters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement("UPDATE sandbox_instances SET container_id = ? WHERE id = ?"))
        .thenReturn(ps);

    repository.updateContainerId("sb-1", "docker-123");

    verify(ps).setString(1, "docker-123");
    verify(ps).setString(2, "sb-1");
    verify(ps).executeUpdate();
  }

  @Test
  public void updateExpiry_existingId_returnsTrue() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement("UPDATE sandbox_instances SET expires_at = ? WHERE id = ?"))
        .thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(1);

    Instant expiresAt = Instant.now();
    boolean updated = repository.updateExpiry("sb-1", expiresAt);

    assertThat(updated).isTrue();
    verify(ps).setTimestamp(1, Timestamp.from(expiresAt));
    verify(ps).setString(2, "sb-1");
  }

  @Test
  public void updateExpiry_missingId_returnsFalse() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(0);

    boolean updated = repository.updateExpiry("sb-missing", Instant.now());

    assertThat(updated).isFalse();
  }

  @Test
  public void updateTerraformOutputs_existingId_bindsParameters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    repository.updateTerraformOutputs("sb-1", "https://url", "arn:tg", "arn:rule", "arn:task");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("SET url = ?, target_group_arn = ?, listener_rule_arn = ?, container_id = ?");

    verify(ps).setString(1, "https://url");
    verify(ps).setString(2, "arn:tg");
    verify(ps).setString(3, "arn:rule");
    verify(ps).setString(4, "arn:task");
    verify(ps).setString(5, "sb-1");
    verify(ps).executeUpdate();
  }

  @Test
  public void updateAccessTokenAndPriority_bindsParameters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    repository.updateAccessTokenAndPriority("sb-1", "new-token", 1004);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("SET access_token = ?, listener_priority = ?");

    verify(ps).setString(1, "new-token");
    verify(ps).setObject(2, 1004, java.sql.Types.INTEGER);
    verify(ps).setString(3, "sb-1");
    verify(ps).executeUpdate();
  }

  @Test
  public void findById_existingId_returnsInstance() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement("SELECT * FROM sandbox_instances WHERE id = ?")).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);

    // Setup ResultSet for 1 row
    when(rs.next()).thenReturn(true);
    setupResultSetRow(rs, "sb-1", SandboxStatus.RUNNING);

    Optional<SandboxInstance> result = repository.findById("sb-1");

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("sb-1");
    assertThat(result.get().getStatus()).isEqualTo(SandboxStatus.RUNNING);
    verify(ps).setString(1, "sb-1");
  }

  @Test
  public void findById_missingId_returnsEmpty() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);

    when(rs.next()).thenReturn(false);

    Optional<SandboxInstance> result = repository.findById("sb-1");

    assertThat(result).isEmpty();
  }

  @Test
  public void findAll_multipleInstances_returnsList() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement("SELECT * FROM sandbox_instances ORDER BY created_at DESC"))
        .thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);

    // Setup ResultSet for 2 rows
    when(rs.next()).thenReturn(true, true, false);

    setupResultSetRow(rs, "sb-1", SandboxStatus.PROVISIONING);
    // Overriding specific column returns for multiple rows
    when(rs.getString("id")).thenReturn("sb-1", "sb-2");

    ImmutableList<SandboxInstance> results = repository.findAll();

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getId()).isEqualTo("sb-1");
    assertThat(results.get(1).getId()).isEqualTo("sb-2");
  }

  @Test
  public void findAll_emptyTable_returnsEmptyList() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);

    when(rs.next()).thenReturn(false);

    ImmutableList<SandboxInstance> results = repository.findAll();

    assertThat(results).isEmpty();
  }

  @Test
  public void delete_existingId_returnsTrue() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(
            "UPDATE sandbox_instances SET status = 'DELETED', deleted_at = NOW() WHERE id = ?"))
        .thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(1);

    boolean deleted = repository.delete("sb-1");

    assertThat(deleted).isTrue();
    verify(ps).setString(1, "sb-1");
  }

  @Test
  public void delete_missingId_returnsFalse() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(0);

    boolean deleted = repository.delete("sb-missing");

    assertThat(deleted).isFalse();
  }

  @Test
  public void softDelete_existingId_returnsTrueAndClearsPii() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(1);

    Instant deletedAt = Instant.now();
    boolean softDeleted = repository.softDelete("sb-1", deletedAt);

    assertThat(softDeleted).isTrue();
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("SET status = ?, deleted_at = ?, pin = '', access_token = '', admin_email = '',")
        .contains("last_destroy_error = NULL");

    verify(ps).setString(1, "DELETED");
    verify(ps).setTimestamp(2, Timestamp.from(deletedAt));
    verify(ps).setString(3, "sb-1");
  }

  @Test
  public void softDelete_missingId_returnsFalse() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(0);

    boolean softDeleted = repository.softDelete("sb-missing", Instant.now());

    assertThat(softDeleted).isFalse();
  }

  @Test
  public void findExpiredForDestruction_bindsCutoffAndMaxAttempts() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    setupResultSetRow(rs, "sb-exp", SandboxStatus.DELETE_FAILED);
    when(rs.getInt("destroy_attempts")).thenReturn(2);
    when(rs.getString("last_destroy_error")).thenReturn("ENI timeout");

    Instant cutoff = Instant.parse("2026-08-15T00:00:00Z");
    ImmutableList<SandboxInstance> results = repository.findExpiredForDestruction(cutoff, 3);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("WHERE status IN ('RUNNING', 'DELETE_FAILED')")
        .contains("AND expires_at < ?")
        .contains("AND destroy_attempts < ?");
    verify(ps).setTimestamp(1, Timestamp.from(cutoff));
    verify(ps).setInt(2, 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getId()).isEqualTo("sb-exp");
    assertThat(results.get(0).getDestroyAttempts()).isEqualTo(2);
    assertThat(results.get(0).getLastDestroyError()).isEqualTo("ENI timeout");
  }

  @Test
  public void recordDestroyFailure_incrementsDestroyAttemptsAndReturnsNewCount() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getInt("destroy_attempts")).thenReturn(3);

    int attempts = repository.recordDestroyFailure("sb-1", "Terraform exit 1");

    assertThat(attempts).isEqualTo(3);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("SET status = ?")
        .contains("destroy_attempts = destroy_attempts + 1")
        .contains("RETURNING destroy_attempts");
    verify(ps).setString(1, "DELETE_FAILED");
    verify(ps).setString(2, "Terraform exit 1");
    verify(ps).setString(3, "sb-1");
  }

  private void setupResultSetRow(ResultSet rs, String id, SandboxStatus status) throws Exception {
    when(rs.getString("id")).thenReturn(id);
    when(rs.getString("city_name")).thenReturn("City");
    when(rs.getString("subdomain")).thenReturn("sub");
    when(rs.getString("civiform_version")).thenReturn("latest");
    when(rs.getString("status")).thenReturn(status.name());
    when(rs.getString("url")).thenReturn("http://url");
    when(rs.getString("admin_email")).thenReturn("admin@email.com");
    when(rs.getString("pin")).thenReturn("1234");
    when(rs.getString("access_token")).thenReturn("token");
    when(rs.getString("container_id")).thenReturn("cont-id");
    when(rs.getInt("host_port")).thenReturn(8080);
    when(rs.getString("database_name")).thenReturn("db_name");
    when(rs.getString("target_group_arn")).thenReturn("tg_arn");
    when(rs.getString("listener_rule_arn")).thenReturn("lr_arn");
    when(rs.getObject("listener_priority", Integer.class)).thenReturn(100);
    when(rs.getString("google_analytics_id")).thenReturn("ga-id");
    when(rs.getString("google_analytics_url")).thenReturn("ga-url");
    when(rs.getInt("destroy_attempts")).thenReturn(0);
    when(rs.getString("last_destroy_error")).thenReturn(null);

    when(rs.getTimestamp("created_at"))
        .thenReturn(new Timestamp(Instant.now().toEpochMilli()));
    when(rs.getTimestamp("expires_at"))
        .thenReturn(new Timestamp(Instant.now().toEpochMilli()));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
  }
}
