package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding Window Counter — the clever approximation that fixes the boundary burst.
 *
 * Combines the memory efficiency of Fixed Window (O(1) per key) with
 * the smoothness of Sliding Window Log (no boundary burst) via a weighted
 * approximation between the current and previous window.
 *
 * Formula:
 *   estimated_count = (prev_window_count × overlap_fraction) + current_window_count
 *
 * where overlap_fraction = 1 - (elapsed_in_current_window / window_size)
 *
 * Example: window = 60s, current time is 15s into the current window.
 *   overlap_fraction = 1 - (15/60) = 0.75
 *   estimated = prev_count × 0.75 + current_count
 *
 * This means at the boundary between two windows, the previous window's
 * requests gradually fade out over the course of the current window.
 * An attacker sending 100 requests at 12:00:59 and 100 at 12:01:01
 * would see an estimated count of ~200 (100 × 0.98 + 100) and be rejected.
 *
 * Error bound: Cloudflare has shown this approximation is within 0.003%
 * of the true sliding window count in production. Good enough for rate
 * limiting, where the alternative (sliding log) costs 480GB of RAM.
 *
 * Thread safety: Two counters per key (current + previous) stored as
 * AtomicLong in a ConcurrentHashMap. The weighted calculation is a
 * local computation with no shared mutable state.
 */
public class SlidingWindowCounterAlgorithm implements LimitAlgorithm {

    private final long maxRequests;
    private final long windowSizeMs;
    private final Clock clock;

    /**
     * Stores two counters per window: (key, windowId) → count.
     * We need the current window and the previous window for the weighted calculation.
     */
    private final ConcurrentHashMap<String, AtomicLong> windows = new ConcurrentHashMap<>();

    public SlidingWindowCounterAlgorithm(long maxRequests, long windowSizeMs) {
        this(maxRequests, windowSizeMs, Clock.systemUTC());
    }

    public SlidingWindowCounterAlgorithm(long maxRequests, long windowSizeMs, Clock clock) {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (windowSizeMs <= 0) throw new IllegalArgumentException("windowSizeMs must be positive");
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = clock.millis();
        long currentWindowId = now / windowSizeMs;
        long previousWindowId = currentWindowId - 1;

        // Time elapsed within the current window
        long elapsedInCurrentWindow = now - (currentWindowId * windowSizeMs);
        double overlapFraction = 1.0 - ((double) elapsedInCurrentWindow / windowSizeMs);

        // Get counters
        long prevCount = getCount(key, previousWindowId);
        long currCount = getCount(key, currentWindowId);

        // Weighted estimate: how many requests are "active" in the sliding window
        double estimatedCount = (prevCount * overlapFraction) + currCount;

        if (estimatedCount + cost > maxRequests) {
            long windowEndMs = (currentWindowId + 1) * windowSizeMs;
            long retryAfterMs = windowEndMs - now;
            long remaining = Math.max(0, (long) (maxRequests - estimatedCount));

            return Decision.deny(retryAfterMs, buildHeaders(remaining, retryAfterMs));
        }

        // Allowed — increment the CURRENT window counter
        String currentKey = key + ":" + currentWindowId;
        AtomicLong counter = windows.computeIfAbsent(currentKey, k -> new AtomicLong(0));
        long newCount = counter.addAndGet(cost);

        // Post-increment check: re-estimate with the new count
        double newEstimate = (prevCount * overlapFraction) + newCount;
        if (newEstimate > maxRequests) {
            // Over-committed due to race — roll back
            counter.addAndGet(-cost);
            long windowEndMs = (currentWindowId + 1) * windowSizeMs;
            long retryAfterMs = windowEndMs - now;
            return Decision.deny(retryAfterMs, buildHeaders(0, retryAfterMs));
        }

        long remaining = Math.max(0, (long) (maxRequests - newEstimate));
        long windowEndMs = (currentWindowId + 1) * windowSizeMs;
        long resetAfterMs = windowEndMs - now;

        // Lazy cleanup: remove windows older than previous
        String staleKey = key + ":" + (previousWindowId - 1);
        windows.remove(staleKey);

        return Decision.allow(remaining, resetAfterMs, buildHeaders(remaining, resetAfterMs));
    }

    /**
     * Returns the estimated count for the current sliding window.
     * Visible for testing.
     */
    double getEstimatedCount(String key) {
        long now = clock.millis();
        long currentWindowId = now / windowSizeMs;
        long previousWindowId = currentWindowId - 1;
        long elapsedInCurrentWindow = now - (currentWindowId * windowSizeMs);
        double overlapFraction = 1.0 - ((double) elapsedInCurrentWindow / windowSizeMs);

        long prevCount = getCount(key, previousWindowId);
        long currCount = getCount(key, currentWindowId);

        return (prevCount * overlapFraction) + currCount;
    }

    private long getCount(String key, long windowId) {
        AtomicLong counter = windows.get(key + ":" + windowId);
        return counter != null ? counter.get() : 0;
    }

    private Map<String, String> buildHeaders(long remaining, long resetAfterMs) {
        return Map.of(
                "X-RateLimit-Limit", String.valueOf(maxRequests),
                "X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)),
                "X-RateLimit-Reset", String.valueOf(resetAfterMs)
        );
    }
}
