package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.typesafe.config.Config;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import models.PersistedDurableJobModel;
import play.Logger;
import play.Logger.ALogger;

/**
 * Posts operational failure alerts to Exygy's {@code #civiform-sre-alerts} Slack channel via an
 * incoming webhook URL.
 *
 * <p>When {@code sandbox.slack.webhookUrl} is unset or blank (such as in local development), alerts
 * are logged at ERROR level instead of throwing.
 */
@Singleton
public class SlackAlertClient {

  private static final ALogger log = Logger.of(SlackAlertClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String DEFAULT_CHANNEL = "#civiform-sre-alerts";

  private final String webhookUrl;
  private final String channel;
  private final HttpClient httpClient;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public SlackAlertClient(Config config) {
    this(
        config,
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
  }

  @VisibleForTesting
  SlackAlertClient(Config config, HttpClient httpClient) {
    checkNotNull(config);
    this.webhookUrl =
        config.hasPath("sandbox.slack.webhookUrl")
            ? config.getString("sandbox.slack.webhookUrl").trim()
            : "";
    String configuredChannel =
        config.hasPath("sandbox.slack.channel")
            ? config.getString("sandbox.slack.channel").trim()
            : "";
    this.channel = configuredChannel.isEmpty() ? DEFAULT_CHANNEL : configuredChannel;
    this.httpClient = checkNotNull(httpClient);
  }

  /**
   * Alerts {@code #civiform-sre-alerts} when a {@link durablejobs.DurableJob} itself crashes with
   * no remaining attempts (mirrors CiviForm's {@code notifyUponFinalFailure}).
   */
  public void sendJobFailureAlert(PersistedDurableJobModel job, String hostName) {
    String text =
        String.format(
            ":rotating_light: *ERROR: CiviForm Sandbox DurableJob failure on `%s`*\n"
                + "• *Job Name:* `%s`\n"
                + "• *Job ID:* `%s`\n"
                + "• *Error Report:*\n```%s```",
            hostName,
            job.getJobName(),
            job.getId(),
            truncate(job.getErrorMessage().orElse("Job is missing error messages."), 2000));
    postMessage(text);
  }

  private void postMessage(String text) {
    if (webhookUrl.isEmpty() || !webhookUrl.startsWith("http")) {
      log.error(
          "Slack webhook URL not configured (sandbox.slack.webhookUrl); alert for {}:\n{}",
          channel,
          text);
      return;
    }

    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("channel", channel);
      payload.put("text", text);
      String body = mapper.writeValueAsString(payload);

      HttpRequest request =
          HttpRequest.newBuilder(URI.create(webhookUrl))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.error(
            "Failed to post Slack alert to {} (HTTP {}): {}",
            channel,
            response.statusCode(),
            response.body());
      } else {
        log.info("Posted failure alert to Slack channel {}", channel);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Interrupted while sending Slack alert to {}: {}", channel, e.getMessage(), e);
    } catch (Exception e) {
      log.error("Failed to send Slack alert to {}: {}", channel, e.getMessage(), e);
    }
  }

  private static String truncate(String s, int maxChars) {
    if (s == null) {
      return "";
    }
    return s.length() <= maxChars ? s : s.substring(0, maxChars) + "... (truncated)";
  }
}
