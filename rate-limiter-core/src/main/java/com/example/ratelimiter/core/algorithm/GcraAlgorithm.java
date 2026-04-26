package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic Cell Rate Algorithm (GCRA) — the mathematically perfect traffic smoother.
 *
 * Adapted from ATM networking (ITU-T I.371). Used by high-frequency trading
 * firms and telecom routers. Tracks exactly ONE variable per key: the
 * Theoretical Arrival Time (TAT).
 *
 * GCRA reduces rate limiting to its absolute mathematical core:
 *
 *   emission_interval = 1000ms / rate_per_second    (time between allowed requests)
 *   burst_tolerance   = max_burst × emission_interval (how far TAT can be in the future)
 *
 * On each request:
 *   1. allow_at = TAT - burst_tolerance
 *   2. If now >= allow_at: ALLOWED. New TAT = max(now, TAT) + emission_interval
 *   3. If now < allow_at:  DENIED.  Retry after (allow_at - now) milliseconds.
 *
 * Why GCRA over Token Bucket:
 * - Token Bucket tracks 2 values (tokens, timestamp). GCRA tracks 1 (TAT).
 * - At 100M keys × 16 bytes (2 longs) vs 8 bytes (1 long):
 *   Token Bucket = 1.6 GB, GCRA = 800 MB. Half the RAM.
 * - GCRA produces mathematically identical results to an ideal sliding window
 *   with O(1) memory, not O(N).
 *
 * The "remaining" calculation from TAT:
 *   remaining = max(0, floor((burst_tolerance - (TAT - now)) / emission_interval))
 *
 * Thread safety: synchronized per-key on the mutable TAT value.
 */
public class GcraAlgorithm implements LimitAlgorithm {

    private final long emissionIntervalMs;
    private final long burstToleranceMs;
    private final long ratePerSecond;
    private final long maxBurst;
    private final Clock clock;

    /** Per-key state: just the TAT (Theoretical Arrival Time) in epoch millis. */
    private final ConcurrentHashMap<String, long[]> tats = new ConcurrentHashMap<>();

    /**
     * @param ratePerSecond sustained rate (requests per second)
     * @param maxBurst      maximum burst size (requests that can fire instantly)
     */
    public GcraAlgorithm(long ratePerSecond, long maxBurst) {
        this(ratePerSecond, maxBurst, Clock.systemUTC());
    }

    public GcraAlgorithm(long ratePerSecond, long maxBurst, Clock clock) {
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be positive");
        if (maxBurst < 0) throw new IllegalArgumentException("maxBurst must be non-negative");
        this.ratePerSecond = ratePerSecond;
        this.maxBurst = maxBurst;
        this.emissionIntervalMs = 1000 / ratePerSecond;
        this.burstToleranceMs = maxBurst * this.emissionIntervalMs;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = clock.millis();
        long incrementMs = cost * emissionIntervalMs;

        // Get or create TAT for this key
        long[] tatHolder = tats.computeIfAbsent(key, k -> new long[]{now});

        synchronized (tatHolder) {
            long tat = tatHolder[0];

            // The earliest time this request could be allowed given the burst budget
            long allowAt = tat - burstToleranceMs;

            if (now >= allowAt) {
                // ALLOWED: advance TAT
                long newTat = Math.max(now, tat) + incrementMs;
                tatHolder[0] = newTat;

                // Compute remaining burst capacity
                long remaining = computeRemaining(now, newTat);

                // Reset time: when will the TAT catch up to now (full refill)?
                long resetAfterMs = Math.max(0, newTat - now);

                return Decision.allow(remaining, resetAfterMs, buildHeaders(remaining, 0));
            } else {
                // DENIED: too early. Tell the client exactly when to retry.
                long retryAfterMs = allowAt - now;

                return Decision.deny(retryAfterMs, buildHeaders(0, retryAfterMs));
            }
        }
    }

    /**
     * Computes remaining burst capacity from the current TAT.
     *
     * remaining = floor((burstTolerance - (TAT - now)) / emissionInterval)
     *
     * If TAT is far in the future, remaining is low (burst consumed).
     * If TAT is close to now, remaining is high (burst available).
     */
    private long computeRemaining(long now, long tat) {
        long gap = tat - now;
        if (gap <= 0) return maxBurst;
        long available = burstToleranceMs - gap;
        if (available <= 0) return 0;
        return available / emissionIntervalMs;
    }

    /** Visible for testing: returns the current TAT for a key, or -1 if not set. */
    long getTat(String key) {
        long[] holder = tats.get(key);
        return holder != null ? holder[0] : -1;
    }

    private Map<String, String> buildHeaders(long remaining, long retryAfterMs) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", ratePerSecond + "/s (burst " + maxBurst + ")");
        headers.put("X-RateLimit-Remaining", String.valueOf(remaining));
        if (retryAfterMs > 0) {
            headers.put("Retry-After", String.valueOf(retryAfterMs));
        }
        return Map.copyOf(headers);
    }
}
