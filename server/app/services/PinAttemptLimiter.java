package services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits PIN guesses at the sandbox PIN gate.
 *
 * <p>The PIN is only 6 digits and {@code POST /sandboxes/:id/access} is public, so without a limit
 * a script can walk the whole space. Each sandbox gets at most {@code maxAttempts} guesses per
 * sliding {@code window}; after that, guesses are refused (without checking the PIN) until the
 * oldest one ages out.
 *
 * <p>Attempts are keyed by sandbox id, not client IP: behind the ALB the request's remote address
 * is the load balancer. The trade-off is that someone hammering a sandbox's gate can lock prospects
 * out of it for up to one window.
 *
 * <p>State is in memory, so it resets when the builder restarts and is not shared between builder
 * tasks.
 */
@Singleton
public class PinAttemptLimiter {

  private final int maxAttempts;
  private final Duration window;
  private final Clock clock;
  private final ConcurrentHashMap<String, Deque<Instant>> attemptsBySandbox =
      new ConcurrentHashMap<>();

  @Inject
  public PinAttemptLimiter(Config config) {
    this(
        config.getInt("sandbox.pinGate.maxAttempts"),
        config.getDuration("sandbox.pinGate.window"),
        Clock.systemUTC());
  }

  PinAttemptLimiter(int maxAttempts, Duration window, Clock clock) {
    this.maxAttempts = maxAttempts;
    this.window = window;
    this.clock = clock;
  }

  /**
   * Counts one guess against the sandbox, unless it is already out of guesses.
   *
   * <p>Call before checking the PIN. The guess is counted up front so that a burst of concurrent
   * requests cannot all pass the check before any of them is recorded.
   *
   * @return empty if the guess may go ahead, otherwise how long until the next one is allowed
   */
  public Optional<Duration> tryAttempt(String sandboxId) {
    Deque<Instant> attempts = attemptsBySandbox.computeIfAbsent(sandboxId, id -> new ArrayDeque<>());
    synchronized (attempts) {
      Instant now = clock.instant();
      while (!attempts.isEmpty() && !attempts.peekFirst().plus(window).isAfter(now)) {
        attempts.pollFirst();
      }
      if (attempts.size() >= maxAttempts) {
        return Optional.of(Duration.between(now, attempts.peekFirst().plus(window)));
      }
      attempts.addLast(now);
      return Optional.empty();
    }
  }

  /**
   * Forgets a sandbox's guesses. Call after a correct PIN, and for ids that are not real sandboxes
   * so that guessing random ids cannot grow the map.
   */
  public void clear(String sandboxId) {
    attemptsBySandbox.remove(sandboxId);
  }
}
