package services;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import play.Logger;
import play.Logger.ALogger;

/**
 * Thin wrapper around the {@code terraform} binary.
 *
 * <p>Separated from {@link TerraformSandboxService} so the orchestration logic can be unit tested
 * against a stubbed CLI without a Terraform installation, AWS credentials, or network access.
 *
 * <p>Every invocation is scoped to a working directory. {@code TerraformSandboxService} creates a
 * fresh one per job, which is what allows concurrent provisioning: Terraform keeps per-directory
 * state in {@code .terraform/}, and two jobs sharing a directory would corrupt each other's
 * provider cache and backend configuration.
 */
public class TerraformCli {

  private static final ALogger log = Logger.of(TerraformCli.class);

  /** Lines of output retained for error reporting. */
  private static final int ERROR_TAIL_LINES = 40;

  private final String binary;
  private final Duration timeout;

  public TerraformCli(String binary, Duration timeout) {
    this.binary = checkNotNull(binary);
    this.timeout = checkNotNull(timeout);
  }

  /**
   * Initialises the working directory against a per-sandbox state key.
   *
   * <p>The backend is configured here rather than in the configuration files because the state key
   * differs per sandbox. Passing it at init time keeps the setting scoped to this working
   * directory, which is what makes it safe under concurrency — unlike Terraform workspaces, where
   * the selection is ambient CLI state and a missed switch silently targets the wrong sandbox.
   */
  public void init(Path workDir, String bucket, String stateKey, String region) {
    run(
        workDir,
        List.of(
            "init",
            "-input=false",
            "-no-color",
            "-backend-config=bucket=" + bucket,
            "-backend-config=key=" + stateKey,
            "-backend-config=region=" + region,
            // Terraform 1.10+ native S3 locking. Without it there is no locking at
            // all and two applies against the same state silently clobber each other.
            "-backend-config=use_lockfile=true"),
        null);
  }

  /** Applies the configuration using a variables file. Streams progress lines to {@code onLine}. */
  public void apply(Path workDir, Path varFile, Consumer<String> onLine) {
    run(
        workDir,
        List.of(
            "apply",
            "-input=false",
            "-no-color",
            "-auto-approve",
            "-var-file=" + varFile.toAbsolutePath()),
        onLine);
  }

  /** Destroys everything in this state. */
  public void destroy(Path workDir, Path varFile, Consumer<String> onLine) {
    run(
        workDir,
        List.of(
            "destroy",
            "-input=false",
            "-no-color",
            "-auto-approve",
            "-var-file=" + varFile.toAbsolutePath()),
        onLine);
  }

  /**
   * Reads the root module's outputs.
   *
   * <p>Returns a flat name to value map; the {@code {value, type, sensitive}} envelope Terraform
   * wraps each output in is unwrapped here so callers do not have to know about it.
   */
  public Map<String, JsonNode> outputs(Path workDir) {
    String json = run(workDir, List.of("output", "-json", "-no-color"), null);

    try {
      JsonNode root = new ObjectMapper().readTree(json);
      Map<String, JsonNode> result = new java.util.LinkedHashMap<>();
      root.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().get("value")));
      return result;
    } catch (IOException e) {
      throw new TerraformException("Could not parse `terraform output -json`", e);
    }
  }

  /**
   * Runs a Terraform subcommand and returns its combined output.
   *
   * @param onLine invoked per output line as it arrives, for progress reporting. May be null.
   */
  private String run(Path workDir, List<String> args, Consumer<String> onLine) {
    List<String> command = new ArrayList<>();
    command.add(binary);
    command.addAll(args);

    log.info("[terraform] {} (in {})", String.join(" ", args), workDir);

    ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());

    // Interleaving stderr into stdout keeps Terraform's error text adjacent to the
    // output that preceded it. Read separately they arrive out of order and the
    // failure appears detached from whatever caused it.
    pb.redirectErrorStream(true);

    // Suppresses the "interactive approval" style hints that make no sense in a
    // server log, and is the documented signal for exactly this use case.
    pb.environment().put("TF_IN_AUTOMATION", "1");

    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new TerraformException("Could not start `" + binary + "`. Is Terraform installed?", e);
    }

    StringBuilder full = new StringBuilder();
    Deque<String> tail = new ArrayDeque<>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

      String line;
      // Draining the pipe as the process runs is not optional. The OS pipe buffer is
      // small; a process that fills it blocks on write forever while we block on
      // waitFor, and the result is a deadlock that looks exactly like a slow apply.
      while ((line = reader.readLine()) != null) {
        full.append(line).append('\n');

        tail.addLast(line);
        if (tail.size() > ERROR_TAIL_LINES) {
          tail.removeFirst();
        }

        if (onLine != null) {
          onLine.accept(line);
        }
      }

      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new TerraformException(
            "terraform " + args.get(0) + " exceeded " + timeout.toMinutes() + " minutes");
      }

      int exit = process.exitValue();
      if (exit != 0) {
        throw new TerraformException(
            "terraform " + args.get(0) + " failed (exit " + exit + "):\n" + String.join("\n", tail));
      }

      return full.toString();

    } catch (IOException e) {
      process.destroyForcibly();
      throw new TerraformException("I/O error running terraform " + args.get(0), e);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      // Restore the flag rather than swallowing it: something is trying to shut this
      // thread pool down, and the caller needs to be able to see that.
      Thread.currentThread().interrupt();
      throw new TerraformException("Interrupted running terraform " + args.get(0), e);
    }
  }

  /** Thrown for any Terraform failure. Message carries the tail of the CLI output. */
  public static class TerraformException extends RuntimeException {
    public TerraformException(String message) {
      super(message);
    }

    public TerraformException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
