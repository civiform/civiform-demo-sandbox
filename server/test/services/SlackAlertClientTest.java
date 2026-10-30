package services;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import models.JobType;
import models.PersistedDurableJobModel;
import models.SandboxInstance;
import org.junit.Test;

/** Unit tests for {@link SlackAlertClient}. */
public class SlackAlertClientTest {

  private static SandboxInstance sampleSandbox() {
    return SandboxInstance.builder()
        .id("sb-deadbeef")
        .cityName("Burlington, VT")
        .subdomain("burlington-vt")
        .databaseName("sb_deadbeef")
        .expiresAt(Instant.parse("2026-08-14T00:00:00Z"))
        .build();
  }

  @Test
  public void sendSandboxDestroyFailureAlert_postsPayloadToConfiguredSlackChannel()
      throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/webhook",
          exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(body, StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
          });
      server.start();

      String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
      Config config =
          ConfigFactory.parseMap(
              Map.of(
                  "sandbox.slack.webhookUrl", webhookUrl,
                  "sandbox.slack.channel", "#civiform-sre-alerts"));

      SlackAlertClient client = new SlackAlertClient(config, HttpClient.newHttpClient());
      client.sendSandboxDestroyFailureAlert(
          sampleSandbox(), 3, "Terraform destroy exited with status 1");

      assertThat(receivedBody.get()).isNotNull();
      JsonNode payload = new ObjectMapper().readTree(receivedBody.get());
      assertThat(payload.get("channel").asText()).isEqualTo("#civiform-sre-alerts");
      assertThat(payload.get("text").asText())
          .contains("failed 3 times")
          .contains("sb-deadbeef")
          .contains("Burlington, VT")
          .contains("burlington-vt")
          .contains("sb_deadbeef")
          .contains("Terraform destroy exited with status 1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void sendJobFailureAlert_postsJobErrorReportToDefaultSreChannel() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/webhook",
          exchange -> {
            receivedBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
          });
      server.start();

      String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
      Config config = ConfigFactory.parseMap(Map.of("sandbox.slack.webhookUrl", webhookUrl));

      PersistedDurableJobModel failedJob =
          PersistedDurableJobModel.builder()
              .id(12L)
              .jobName("DESTROY_EXPIRED_SANDBOXES")
              .jobType(JobType.RECURRING)
              .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
              .remainingAttempts(0)
              .errorMessage("Postgres connection pool exhausted")
              .build();

      SlackAlertClient client = new SlackAlertClient(config, HttpClient.newHttpClient());
      client.sendJobFailureAlert(failedJob, "sandbox.civiform.dev");

      JsonNode payload = new ObjectMapper().readTree(receivedBody.get());
      assertThat(payload.get("channel").asText()).isEqualTo("#civiform-sre-alerts");
      assertThat(payload.get("text").asText())
          .contains("DESTROY_EXPIRED_SANDBOXES")
          .contains("sandbox.civiform.dev")
          .contains("Postgres connection pool exhausted");
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void sendSandboxDestroyFailureAlert_blankOrPlaceholderUrlLogsWithoutThrowing() {
    SlackAlertClient emptyClient =
        new SlackAlertClient(
            ConfigFactory.parseMap(Map.of("sandbox.slack.webhookUrl", "")),
            HttpClient.newHttpClient());
    emptyClient.sendSandboxDestroyFailureAlert(sampleSandbox(), 3, "boom");

    SlackAlertClient placeholderClient =
        new SlackAlertClient(
            ConfigFactory.parseMap(
                Map.of("sandbox.slack.webhookUrl", "REPLACE_WITH_SLACK_SRE_WEBHOOK_URL")),
            HttpClient.newHttpClient());
    placeholderClient.sendSandboxDestroyFailureAlert(sampleSandbox(), 3, "boom");
  }
}
