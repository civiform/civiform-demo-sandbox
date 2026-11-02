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
import org.junit.Test;

/** Unit tests for {@link SlackAlertClient}. */
public class SlackAlertClientTest {

  private static PersistedDurableJobModel sampleFailedJob() {
    return PersistedDurableJobModel.builder()
        .id(12L)
        .jobName("OLD_JOB_CLEANUP")
        .jobType(JobType.RECURRING)
        .executionTime(Instant.parse("2026-08-15T02:00:00Z"))
        .remainingAttempts(0)
        .errorMessage("Postgres connection pool exhausted")
        .build();
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

      SlackAlertClient client = new SlackAlertClient(config, HttpClient.newHttpClient());
      client.sendJobFailureAlert(sampleFailedJob(), "sandbox.civiform.dev");

      JsonNode payload = new ObjectMapper().readTree(receivedBody.get());
      assertThat(payload.get("channel").asText()).isEqualTo("#civiform-sre-alerts");
      assertThat(payload.get("text").asText())
          .contains("OLD_JOB_CLEANUP")
          .contains("sandbox.civiform.dev")
          .contains("Postgres connection pool exhausted");
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void sendJobFailureAlert_blankOrPlaceholderUrlLogsWithoutThrowing() {
    SlackAlertClient emptyClient =
        new SlackAlertClient(
            ConfigFactory.parseMap(Map.of("sandbox.slack.webhookUrl", "")),
            HttpClient.newHttpClient());
    emptyClient.sendJobFailureAlert(sampleFailedJob(), "localhost");

    SlackAlertClient placeholderClient =
        new SlackAlertClient(
            ConfigFactory.parseMap(
                Map.of("sandbox.slack.webhookUrl", "REPLACE_WITH_SLACK_SRE_WEBHOOK_URL")),
            HttpClient.newHttpClient());
    placeholderClient.sendJobFailureAlert(sampleFailedJob(), "localhost");
  }
}
