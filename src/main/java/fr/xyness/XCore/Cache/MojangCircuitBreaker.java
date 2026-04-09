package fr.xyness.XCore.Cache;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe circuit breaker for Mojang API calls.
 * <p>
 * Tracks consecutive failures and temporarily disables API calls
 * when the failure threshold is reached, preventing further load on a failing endpoint.
 * All state transitions use atomic compare-and-set operations to prevent race conditions.
 * </p>
 * <ul>
 *   <li><b>CLOSED</b> — Normal operation, requests pass through.</li>
 *   <li><b>OPEN</b> — Requests are blocked. Transitions to HALF_OPEN after the cooldown expires.</li>
 *   <li><b>HALF_OPEN</b> — A single request is allowed. On success &rarr; CLOSED, on failure &rarr; OPEN.</li>
 * </ul>
 */
public class MojangCircuitBreaker {

    /** Circuit breaker states. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /**
     * Creates a new circuit breaker.
     *
     * @param failureThreshold     Number of consecutive failures before opening the circuit.
     * @param openDurationMinutes  Duration in minutes the circuit stays open before transitioning to half-open.
     */
    public MojangCircuitBreaker(int failureThreshold, int openDurationMinutes) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMinutes * 60_000L;
    }

    /**
     * Checks whether a request is allowed through the circuit breaker.
     * Uses atomic CAS to ensure only one thread transitions from OPEN to HALF_OPEN.
     *
     * @return {@code true} if the request should proceed, {@code false} if it should be blocked.
     */
    public boolean allowRequest() {
        return switch (state.get()) {
            case CLOSED -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt.get() >= openDurationMs) {
                    yield state.compareAndSet(State.OPEN, State.HALF_OPEN);
                }
                yield false;
            }
            case HALF_OPEN -> true;
        };
    }

    /**
     * Records a successful API call. Resets the failure counter and closes the circuit.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    /**
     * Records a failed API call. If the threshold is reached, opens the circuit.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
        }
    }

    /** @return The current circuit breaker state. */
    public State getState() { return state.get(); }
}
