package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed Window Counter — the simplest rate limiting algorithm.
 *
 * Restricts users within discrete time blocks (e.g., 100 requests per minute).
 * Each window is a separate counter keyed by epoch time.
 *
 * Implementation: atomic counter per (key, window) pair.
 * Memory: O(1) per key — only the current window's counter is live.
 * Old windows are lazily garbage-collected.
 *
 * THE FATAL FLAW (Blog Part 3):
 * The "Burst at the Boundary" problem. An attacker sends 100 requests at
 * 12:00:59 and 100 at 12:01:01. Neither window is violated, but the
 * infrastructure sees 200 requests in 2 seconds. This is not a theoretical
 * concern — synchronized API scraping scripts exploit this at scale.
 *
 * This implementation is included for completeness and comparison.
 * Production systems should use TokenBucket or GCRA instead.
 *
 * Thread safety: ConcurrentHashMap + AtomicLong.incrementAndGet() ensures
 * atomic increment without locks. Multiple threads incrementing the same
 * window counter will not produce lost updates.
 */
public class FixedWindowAlgorithm implements LimitAlgorithm {

    private final long maxRequests;
    private final long windowSizeMs;
    private final Clock clock;

    /**
     * Each entry: "key:windowId" → counter.
     * windowId = currentTimeMs / windowSizeMs (epoch-aligned).
     */
    private final ConcurrentHashMap<String, AtomicLong> windows = new ConcurrentHashMap<>();

    public FixedWindowAlgorithm(long maxRequests, long windowSizeMs) {
        this(maxRequests, windowSizeMs, Clock.systemUTC());
    }

    public FixedWindowAlgorithm(long maxRequests, long windowSizeMs, Clock clock) {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (windowSizeMs <= 0) throw new IllegalArgumentException("windowSizeMs must be positive");
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.clock = clock;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = clock.millis();
        long windowId = now / windowSizeMs;
        String windowKey = key + ":" + windowId;

        AtomicLong counter = windows.computeIfAbsent(windowKey, k -> new AtomicLong(0));
        long currentCount = counter.get();

        if (currentCount + cost > maxRequests) {
            // Denied: window is full
            long windowEndMs = (windowId + 1) * windowSizeMs;
            long retryAfterMs = windowEndMs - now;

            return Decision.deny(retryAfterMs, buildHeaders(maxRequests - currentCount, retryAfterMs));
        }

        long newCount = counter.addAndGet(cost);

        // Post-increment check: another thread may have raced past the check above
        if (newCount > maxRequests) {
            // We over-committed — roll back and deny
            counter.addAndGet(-cost);
            long windowEndMs = (windowId + 1) * windowSizeMs;
            long retryAfterMs = windowEndMs - now;
            return Decision.deny(retryAfterMs, buildHeaders(0, retryAfterMs));
        }

        long remaining = maxRequests - newCount;
        long windowEndMs = (windowId + 1) * windowSizeMs;
        long resetAfterMs = windowEndMs - now;

        // Lazy cleanup: remove previous window
        String prevKey = key + ":" + (windowId - 1);
        windows.remove(prevKey);

        return Decision.allow(remaining, resetAfterMs, buildHeaders(remaining, resetAfterMs));
    }

    private Map<String, String> buildHeaders(long remaining, long resetAfterMs) {
        return Map.of(
                "X-RateLimit-Limit", String.valueOf(maxRequests),
                "X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)),
                "X-RateLimit-Reset", String.valueOf(resetAfterMs)
        );
    }

    /** Visible for testing: returns the current window counter for a key. */
    long getCurrentCount(String key) {
        long windowId = clock.millis() / windowSizeMs;
        AtomicLong counter = windows.get(key + ":" + windowId);
        return counter != null ? counter.get() : 0;
    }
}
