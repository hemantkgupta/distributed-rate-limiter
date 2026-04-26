package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Bucket — the industry standard (from scratch, no Bucket4j).
 *
 * A virtual bucket fills at a constant rate. Requests drain it. If the
 * bucket is empty, requests are denied until tokens refill.
 *
 * State: O(1) per key — exactly [tokensRemaining, lastRefillTimestamp].
 *
 * Key property: BURST ALLOWANCE. If a user hasn't made a request in
 * 10 minutes, their bucket fills to capacity. Their next burst of
 * requests fires instantly until the bucket drains, then they're
 * clamped to the sustained refill rate.
 *
 * This is the fundamental difference from Leaky Bucket: Token Bucket
 * allows controlled bursts, Leaky Bucket enforces strict constant rate.
 *
 * The "lazy refill" optimization: we don't run a background thread to
 * add tokens periodically. Instead, on each request, we compute how
 * many tokens SHOULD have been added since the last check:
 *   elapsed = now - lastRefill
 *   refilled = elapsed × refillRate
 *   tokens = min(capacity, currentTokens + refilled)
 *
 * Thread safety: synchronized per-key on the BucketState object.
 */
public class TokenBucketAlgorithm implements LimitAlgorithm {

    private final long capacity;
    private final double refillRatePerMs;
    private final Clock clock;

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity         maximum tokens the bucket can hold (burst allowance)
     * @param refillRatePerSec tokens added per second (sustained rate)
     */
    public TokenBucketAlgorithm(long capacity, double refillRatePerSec) {
        this(capacity, refillRatePerSec, Clock.systemUTC());
    }

    public TokenBucketAlgorithm(long capacity, double refillRatePerSec, Clock clock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (refillRatePerSec <= 0) throw new IllegalArgumentException("refillRate must be positive");
        this.capacity = capacity;
        this.refillRatePerMs = refillRatePerSec / 1000.0;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = clock.millis();
        BucketState state = buckets.computeIfAbsent(key, k -> new BucketState(capacity, now));

        synchronized (state) {
            // 1. Lazy refill: compute tokens added since last check
            long elapsed = now - state.lastRefillMs;
            double refilled = elapsed * refillRatePerMs;
            double tokens = Math.min(capacity, state.tokens + refilled);
            state.lastRefillMs = now;

            // 2. Check if we have enough tokens
            if (tokens < cost) {
                state.tokens = tokens; // Save the refilled state even on deny
                // Calculate when enough tokens will be available
                double deficit = cost - tokens;
                long retryAfterMs = (long) Math.ceil(deficit / refillRatePerMs);

                return Decision.deny(retryAfterMs, buildHeaders(
                        Math.max(0, (long) tokens), retryAfterMs));
            }

            // 3. Consume tokens
            state.tokens = tokens - cost;
            long remaining = (long) state.tokens;
            long resetAfterMs = (long) Math.ceil((capacity - state.tokens) / refillRatePerMs);

            return Decision.allow(remaining, resetAfterMs, buildHeaders(remaining, resetAfterMs));
        }
    }

    /** Visible for testing. */
    double getAvailableTokens(String key) {
        BucketState state = buckets.get(key);
        if (state == null) return capacity;
        synchronized (state) {
            long elapsed = clock.millis() - state.lastRefillMs;
            return Math.min(capacity, state.tokens + elapsed * refillRatePerMs);
        }
    }

    private Map<String, String> buildHeaders(long remaining, long resetAfterMs) {
        return Map.of(
                "X-RateLimit-Limit", String.valueOf(capacity),
                "X-RateLimit-Remaining", String.valueOf(remaining),
                "X-RateLimit-Reset", String.valueOf(resetAfterMs)
        );
    }

    private static class BucketState {
        double tokens;
        long lastRefillMs;

        BucketState(double tokens, long lastRefillMs) {
            this.tokens = tokens;
            this.lastRefillMs = lastRefillMs;
        }
    }
}
