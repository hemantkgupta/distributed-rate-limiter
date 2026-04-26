package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaky Bucket — efficient lazy version for strict traffic shaping.
 *
 * The classic leaky bucket drains at a constant rate. The naive implementation
 * uses a background thread that pops requests from a FIFO queue at fixed
 * intervals — O(N) threads for N buckets, massive CPU overhead.
 *
 * This implementation uses the "efficient lazy" form: compute state on-demand.
 * No background threads, no queues. O(1) memory per key: just [level, lastUpdate].
 *
 * On each request:
 *   1. Compute elapsed time since lastUpdate
 *   2. Mathematically "leak" elapsed × leakRate from the current level
 *   3. Check if level + cost <= capacity
 *   4. If yes: accept, add cost to level, update lastUpdate
 *   5. If no: reject with retryAfter = (level + cost - capacity) / leakRate
 *
 * The key difference from Token Bucket:
 * - Token Bucket: fills UP (refills tokens), allows BURST up to capacity
 * - Leaky Bucket: drains DOWN (leaks water), enforces STRICT constant outflow
 *
 * Semantically identical math (inverted), but the intended behavior differs:
 * - Token Bucket: "you can burst, then wait for refill" (API rate limiting)
 * - Leaky Bucket: "traffic exits at a constant rate, no bursts" (traffic shaping)
 *
 * Thread safety: ConcurrentHashMap with synchronized per-key via computeIfAbsent.
 * The bucket state mutation is synchronized on the BucketState object itself.
 */
public class LeakyBucketAlgorithm implements LimitAlgorithm {

    private final long capacity;
    private final double leakRatePerMs;
    private final Clock clock;

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity       maximum number of requests the bucket can hold
     * @param leakRatePerSec rate at which the bucket drains (requests per second)
     */
    public LeakyBucketAlgorithm(long capacity, double leakRatePerSec) {
        this(capacity, leakRatePerSec, Clock.systemUTC());
    }

    public LeakyBucketAlgorithm(long capacity, double leakRatePerSec, Clock clock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (leakRatePerSec <= 0) throw new IllegalArgumentException("leakRate must be positive");
        this.capacity = capacity;
        this.leakRatePerMs = leakRatePerSec / 1000.0;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = clock.millis();
        BucketState state = buckets.computeIfAbsent(key, k -> new BucketState(0, now));

        synchronized (state) {
            // 1. Compute leak since last update
            long elapsed = now - state.lastUpdateMs;
            double leaked = elapsed * leakRatePerMs;
            double newLevel = Math.max(0, state.level - leaked);

            // 2. Check if the request fits
            if (newLevel + cost > capacity) {
                // Denied: bucket is too full. Calculate when enough space will leak out.
                double excess = (newLevel + cost) - capacity;
                long retryAfterMs = (long) Math.ceil(excess / leakRatePerMs);

                state.level = newLevel;
                state.lastUpdateMs = now;

                return Decision.deny(retryAfterMs, buildHeaders(
                        Math.max(0, (long) (capacity - newLevel)), retryAfterMs));
            }

            // 3. Allowed: add to the bucket
            state.level = newLevel + cost;
            state.lastUpdateMs = now;

            long remaining = Math.max(0, (long) (capacity - state.level));
            long resetAfterMs = (long) Math.ceil(state.level / leakRatePerMs);

            return Decision.allow(remaining, resetAfterMs, buildHeaders(remaining, resetAfterMs));
        }
    }

    /** Visible for testing. */
    double getCurrentLevel(String key) {
        BucketState state = buckets.get(key);
        if (state == null) return 0;
        synchronized (state) {
            long elapsed = clock.millis() - state.lastUpdateMs;
            return Math.max(0, state.level - elapsed * leakRatePerMs);
        }
    }

    private Map<String, String> buildHeaders(long remaining, long resetAfterMs) {
        return Map.of(
                "X-RateLimit-Limit", String.valueOf(capacity),
                "X-RateLimit-Remaining", String.valueOf(remaining),
                "X-RateLimit-Reset", String.valueOf(resetAfterMs)
        );
    }

    /** Mutable state for a single bucket. Synchronized externally. */
    private static class BucketState {
        double level;
        long lastUpdateMs;

        BucketState(double level, long lastUpdateMs) {
            this.level = level;
            this.lastUpdateMs = lastUpdateMs;
        }
    }
}
