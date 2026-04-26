package com.example.ratelimiter.core.resilience;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import com.example.ratelimiter.core.algorithm.TokenBucketAlgorithm;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-Open Circuit Breaker — the last line of defense.
 *
 * Blog Part 7: "If the Rate Limiting database dies, every single route
 * on your API is broken unless you handle it explicitly."
 *
 * The system operates under a FAIL-OPEN policy. If Redis is down or slow:
 * 1. Allow traffic through to the backend (don't block legitimate users)
 * 2. Fall back to a local token bucket (degraded accuracy, still protects backend)
 * 3. Record metrics for observability
 *
 * Why fail-open and not fail-closed?
 * "The consequences of accidentally throttling all legitimate business traffic
 * (cart checkouts, massive enterprise syncs) dramatically outweigh the temporary
 * risk of a backend traffic surge." — Blog Part 7
 *
 * Circuit breaker states:
 *   CLOSED: Redis is healthy. All requests go through Redis.
 *   OPEN:   Redis is down. Requests served from local fallback bucket.
 *   HALF_OPEN: Probing Redis with a test request to see if it's recovered.
 *
 * Transition logic:
 *   CLOSED → OPEN:     after `failureThreshold` consecutive failures
 *   OPEN → HALF_OPEN:  after `openDurationMs` elapses
 *   HALF_OPEN → CLOSED: if the probe request succeeds
 *   HALF_OPEN → OPEN:   if the probe request fails
 *
 * The local fallback bucket provides DEGRADED rate limiting during Redis outages.
 * It's not globally accurate (per-pod instead of global), but it prevents
 * a single client from overwhelming the backend. Better than nothing.
 */
public class FailOpenCircuitBreaker implements LimitAlgorithm {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final LimitAlgorithm primaryAlgorithm;    // Redis-backed
    private final LimitAlgorithm fallbackAlgorithm;   // Local token bucket
    private final int failureThreshold;
    private final long openDurationMs;
    private final long timeoutMs;
    private final Clock clock;

    /** Current circuit state. */
    private volatile State state = State.CLOSED;

    /** Consecutive failures while CLOSED. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** When the circuit opened (for half-open transition timing). */
    private final AtomicLong openedAtMs = new AtomicLong(0);

    // --- Metrics ---
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong primarySuccesses = new AtomicLong(0);
    private final AtomicLong fallbackInvocations = new AtomicLong(0);
    private final AtomicLong failOpenPassthroughs = new AtomicLong(0);

    /**
     * @param primaryAlgorithm   Redis-backed algorithm (the normal path)
     * @param fallbackCapacity   capacity for the local fallback bucket
     * @param fallbackRate       refill rate for the local fallback bucket (req/s)
     * @param failureThreshold   consecutive failures before opening circuit
     * @param openDurationMs     how long to stay OPEN before probing HALF_OPEN
     * @param timeoutMs          max time to wait for primary algorithm response
     */
    public FailOpenCircuitBreaker(LimitAlgorithm primaryAlgorithm,
                                   long fallbackCapacity, double fallbackRate,
                                   int failureThreshold, long openDurationMs,
                                   long timeoutMs) {
        this(primaryAlgorithm, fallbackCapacity, fallbackRate,
                failureThreshold, openDurationMs, timeoutMs, Clock.systemUTC());
    }

    public FailOpenCircuitBreaker(LimitAlgorithm primaryAlgorithm,
                                   long fallbackCapacity, double fallbackRate,
                                   int failureThreshold, long openDurationMs,
                                   long timeoutMs, Clock clock) {
        this.primaryAlgorithm = primaryAlgorithm;
        this.fallbackAlgorithm = new TokenBucketAlgorithm(fallbackCapacity, fallbackRate, clock);
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.timeoutMs = timeoutMs;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        totalRequests.incrementAndGet();

        return switch (state) {
            case CLOSED -> handleClosed(key, cost);
            case OPEN -> handleOpen(key, cost);
            case HALF_OPEN -> handleHalfOpen(key, cost);
        };
    }

    private Decision handleClosed(String key, long cost) {
        try {
            Decision decision = callPrimaryWithTimeout(key, cost);
            // Success — reset failure counter
            consecutiveFailures.set(0);
            primarySuccesses.incrementAndGet();
            return decision;
        } catch (Exception ex) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold) {
                tripCircuit();
            }
            return failOpen(key, cost);
        }
    }

    private Decision handleOpen(String key, long cost) {
        // Check if it's time to probe
        long elapsed = clock.millis() - openedAtMs.get();
        if (elapsed >= openDurationMs) {
            state = State.HALF_OPEN;
            return handleHalfOpen(key, cost);
        }

        // Still OPEN — use fallback
        return failOpen(key, cost);
    }

    private Decision handleHalfOpen(String key, long cost) {
        // Probe: try one request through Redis
        try {
            Decision decision = callPrimaryWithTimeout(key, cost);
            // Probe succeeded — close the circuit
            state = State.CLOSED;
            consecutiveFailures.set(0);
            primarySuccesses.incrementAndGet();
            return decision;
        } catch (Exception ex) {
            // Probe failed — re-open the circuit
            tripCircuit();
            return failOpen(key, cost);
        }
    }

    /**
     * Calls the primary algorithm with a timeout.
     * In production, this wraps the Redis call in a CompletableFuture with timeout.
     * For simplicity, we catch exceptions (timeout, connection failure).
     */
    private Decision callPrimaryWithTimeout(String key, long cost) {
        // The primary algorithm call. If Redis is down, this throws.
        return primaryAlgorithm.checkAndConsume(key, cost);
    }

    /**
     * Fail-open: use the local fallback bucket if available,
     * otherwise allow the request unconditionally.
     */
    private Decision failOpen(String key, long cost) {
        fallbackInvocations.incrementAndGet();

        try {
            // Use local fallback for degraded-but-still-bounded rate limiting
            return fallbackAlgorithm.checkAndConsume(key, cost);
        } catch (Exception fallbackEx) {
            // Even the fallback failed — unconditional allow (fail completely open)
            failOpenPassthroughs.incrementAndGet();
            return Decision.allow(0, 0, Map.of(
                    "X-RateLimit-Source", "fail-open-passthrough"
            ));
        }
    }

    private void tripCircuit() {
        state = State.OPEN;
        openedAtMs.set(clock.millis());
    }

    // --- Metrics ---

    public State getState() { return state; }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getPrimarySuccesses() { return primarySuccesses.get(); }
    public long getFallbackInvocations() { return fallbackInvocations.get(); }
    public long getFailOpenPassthroughs() { return failOpenPassthroughs.get(); }
    public int getConsecutiveFailures() { return consecutiveFailures.get(); }

    /** For testing: force a state transition. */
    void setState(State newState) { this.state = newState; }
}
