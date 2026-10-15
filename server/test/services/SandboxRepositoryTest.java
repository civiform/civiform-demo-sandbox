package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import play.db.ConnectionCallable;
import play.db.Database;

/** Unit tests for {@link SandboxRepository}. */
public class SandboxRepositoryTest {

  @Test
  public void ensureSchemaInitialized_executesInitPostgresSql() throws Exception {
    Database db = mock(Database.class);
    Connection conn = mock(Connection.class);
    Statement statement = mock(Statement.class);
    when(conn.createStatement()).thenReturn(statement);
    when(db.withConnection(ArgumentMatchers.<ConnectionCallable<Object>>any()))
        .thenAnswer(
            invocation -> {
              ConnectionCallable<?> callable = invocation.getArgument(0);
              return callable.call(conn);
            });

    SandboxRepository repository = new SandboxRepository(db);
    repository.ensureSchemaInitialized();

    String expectedSql = Files.readString(Path.of("../init_postgres.sql"), StandardCharsets.UTF_8);
    verify(statement).execute(expectedSql);
    assertThat(expectedSql)
        .contains("CREATE SEQUENCE IF NOT EXISTS sandbox_port_seq")
        .contains("CREATE SEQUENCE IF NOT EXISTS sandbox_listener_priority_seq")
        .contains("CREATE TABLE IF NOT EXISTS sandbox_instances")
        .contains("CREATE UNIQUE INDEX IF NOT EXISTS sandbox_instances_subdomain_live_idx");
  }
}
