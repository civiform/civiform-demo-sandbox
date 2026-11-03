package services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import models.SandboxInstance;
import models.SandboxStatus;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.ws.WSClient;
import play.test.WithApplication;

/**
 * Integration test for {@link SeedingEngine}.
 *
 * <p>Spins up a real {@link HttpServer} that mimics CiviForm's {@code /dev/seedProgramsHeadless}
 * endpoint, and uses Play's real {@link WSClient} (via {@link WithApplication}) to verify
 * end-to-end HTTP behavior including retries.
 *
 * <p>Excluded from default {@code sbt test} via build.sbt's IntegrationTest filter. Run explicitly:
 *
 * <pre>{@code sbt 'testOnly *SeedingEngineIntegrationTest'}</pre>
 */
public class SeedingEngineIntegrationTest extends WithApplication {

  @Override
  protected Application provideApplication() {
    return new GuiceApplicationBuilder().build();
  }

  private static SandboxInstance testSandbox(String baseUrl) {
    return SandboxInstance.builder()
        .id("sb-int-test")
        .cityName("Burlington, VT")
        .subdomain("burlington-vt")
        .civiformVersion("latest")
        .status(SandboxStatus.RUNNING)
        .url(baseUrl)
        .adminEmail("test@exygy.com")
        .pin("123456")
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(Duration.ofDays(30)))
        .build();
  }

  @Test
  public void seedSandbox_postsToDevSeedEndpointAndReturnsTrueOn200() throws Exception {
    AtomicInteger hitCount = new AtomicInteger(0);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/dev/seedProgramsHeadless",
          exchange -> {
            hitCount.incrementAndGet();
            // Verify it's a POST
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
          });
      server.start();

      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      SandboxRepository repository = mock(SandboxRepository.class);
      WSClient ws = app.injector().instanceOf(WSClient.class);

      SeedingEngine engine = new SeedingEngine(ws, repository);
      SandboxInstance sandbox = testSandbox(baseUrl);

      Boolean result = engine.seedSandbox(sandbox).toCompletableFuture().get();

      assertThat(result).isTrue();
      assertThat(hitCount.get()).isEqualTo(1);

      // Verify status was set to SEEDING
      verify(repository).updateStatus("sb-int-test", SandboxStatus.SEEDING);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void seedSandbox_returnsFalseWhenEndpointAlwaysReturns500() throws Exception {
    AtomicInteger hitCount = new AtomicInteger(0);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/dev/seedProgramsHeadless",
          exchange -> {
            hitCount.incrementAndGet();
            byte[] body = "Internal Server Error".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();

      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      SandboxRepository repository = mock(SandboxRepository.class);
      WSClient ws = app.injector().instanceOf(WSClient.class);

      SeedingEngine engine = new SeedingEngine(ws, repository);
      SandboxInstance sandbox = testSandbox(baseUrl);

      Boolean result = engine.seedSandbox(sandbox).toCompletableFuture().get();

      assertThat(result).isFalse();
      // Should have retried MAX_SEED_ATTEMPTS (3) times
      assertThat(hitCount.get()).isEqualTo(3);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void seedSandbox_retriesOnFailureThenSucceeds() throws Exception {
    AtomicInteger hitCount = new AtomicInteger(0);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/dev/seedProgramsHeadless",
          exchange -> {
            int attempt = hitCount.incrementAndGet();
            if (attempt == 1) {
              // First attempt: simulate transient failure
              byte[] body = "DB transaction conflict".getBytes();
              exchange.sendResponseHeaders(500, body.length);
              exchange.getResponseBody().write(body);
            } else {
              // Subsequent attempts: succeed
              exchange.sendResponseHeaders(200, 0);
            }
            exchange.close();
          });
      server.start();

      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      SandboxRepository repository = mock(SandboxRepository.class);
      WSClient ws = app.injector().instanceOf(WSClient.class);

      SeedingEngine engine = new SeedingEngine(ws, repository);
      SandboxInstance sandbox = testSandbox(baseUrl);

      Boolean result = engine.seedSandbox(sandbox).toCompletableFuture().get();

      assertThat(result).isTrue();
      assertThat(hitCount.get()).isEqualTo(2); // Failed once, succeeded on retry
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void seedSandbox_returnsFalseWhenServerIsDown() throws Exception {
    SandboxRepository repository = mock(SandboxRepository.class);
    WSClient ws = app.injector().instanceOf(WSClient.class);

    SeedingEngine engine = new SeedingEngine(ws, repository);
    // Point at a port that nothing is listening on
    SandboxInstance sandbox = testSandbox("http://127.0.0.1:19999");

    Boolean result = engine.seedSandbox(sandbox).toCompletableFuture().get();

    assertThat(result).isFalse();
    verify(repository).updateStatus("sb-int-test", SandboxStatus.SEEDING);
  }
}
