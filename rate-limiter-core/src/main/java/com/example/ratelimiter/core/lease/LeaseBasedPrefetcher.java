package com.example.ratelimiter.core.lease;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lease-Based Prefetcher — the L1 shadow buffer for eliminating Redis round-trips.
 *
 * Blog Part 6: "Instead of asking Redis for one execution permit, the engine
 * anticipates high volume and requests a bulk lease of 50 tokens."
 *
 * This is the critical optimization for hyperscale (>100K req/sec).
 * Without it, every rate-limit check requires a Redis round-trip.
 * With it, only 1 in N requests touches Redis (where N = lease size).
 *
 * How it works:
 * 1. On first request for a key, fetch a lease of N tokens from Redis.
 * 2. Store the lease locally in an AtomicInteger.
 * 3. For the next N-1 requests, decrement the local counter. ZERO Redis calls.
 * 4. When the local counter runs out, fetch a new lease asynchronously.
 *
 * Jitter (Blog Part 6):
 * If 10 Kubernetes pods all fetch a 50-token lease simultaneously, they
 * consume 500 tokens from the user's limit at once — starving other pods.
 * Solution: jittered lease sizes. Each pod requests between 25 and 75 tokens
 * (random uniform), desynchronizing their refetch cycles.
 *
 * Accuracy tradeoff: lease-based prefetching is "eventually accurate."
 * A user's true remaining count is distributed across N pods' local caches
 * plus Redis. The global view is stale by up to (lease_size × pod_count)
 * tokens. For rate limiting, this is acceptable — the alternative is
 * serializing every request through Redis.
 *
 * Thread safety: AtomicInteger for local counters, ConcurrentHashMap for
 * per-key lease state. Lock-free on the hot path (local decrement).
 */
public class LeaseBasedPrefetcher implements LimitAlgorithm {

    private final LimitAlgorithm backingAlgorithm; // Redis-backed algorithm
    private final int baseLeaseSizeTokens;
    private final double jitterFraction; // 0.0 = no jitter, 0.5 = ±50% of base

    /** Per-key local lease state. */
    private final ConcurrentHashMap<String, LocalLease> leases = new ConcurrentHashMap<>();

    /**
     * @param backingAlgorithm   the Redis-backed algorithm to fetch leases from
     * @param baseLeaseSizeTokens base lease size (actual will be jittered ±jitterFraction)
     * @param jitterFraction     how much to randomize the lease size (0.0–1.0)
     */
    public LeaseBasedPrefetcher(LimitAlgorithm backingAlgorithm, int baseLeaseSizeTokens,
                                 double jitterFraction) {
        if (baseLeaseSizeTokens <= 0) throw new IllegalArgumentException("lease size must be positive");
        if (jitterFraction < 0 || jitterFraction > 1) throw new IllegalArgumentException("jitter must be 0.0–1.0");
        this.backingAlgorithm = backingAlgorithm;
        this.baseLeaseSizeTokens = baseLeaseSizeTokens;
        this.jitterFraction = jitterFraction;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        LocalLease lease = leases.computeIfAbsent(key, k -> new LocalLease());

        // Fast path: try to consume from local lease (no Redis call)
        int remaining = lease.localTokens.addAndGet((int) -cost);
        if (remaining >= 0) {
            lease.localHits.incrementAndGet();
            long globalRemaining = Math.max(0, remaining);
            return Decision.allow(globalRemaining, 0, buildHeaders(globalRemaining, true));
        }

        // Local lease exhausted: need to fetch a new one from Redis
        // Roll back the failed decrement
        lease.localTokens.addAndGet((int) cost);

        return fetchNewLeaseAndConsume(key, lease, cost);
    }

    /**
     * Fetches a new lease from the backing (Redis) algorithm.
     *
     * Synchronized per-key to prevent multiple threads from racing to fetch
     * leases simultaneously (which would consume N × leaseSize tokens at once).
     */
    private Decision fetchNewLeaseAndConsume(String key, LocalLease lease, long cost) {
        synchronized (lease) {
            // Double-check: another thread may have already refreshed
            int currentLocal = lease.localTokens.get();
            if (currentLocal >= cost) {
                int remaining = lease.localTokens.addAndGet((int) -cost);
                if (remaining >= 0) {
                    return Decision.allow(remaining, 0, buildHeaders(remaining, true));
                }
                lease.localTokens.addAndGet((int) cost); // Roll back
            }

            // Compute jittered lease size
            int leaseSize = computeJitteredLeaseSize();
            lease.redisFetches.incrementAndGet();

            // Fetch from Redis: consume leaseSize tokens from the global pool
            Decision redisDeci = backingAlgorithm.checkAndConsume(key, leaseSize);

            if (!redisDeci.allowed()) {
                // Redis denied the lease — the user is truly over limit
                // Try to get what we can: request just the cost
                Decision singleDecision = backingAlgorithm.checkAndConsume(key, cost);
                return singleDecision;
            }

            // Lease granted: load local tokens (minus the current request's cost)
            int tokensAfterConsume = leaseSize - (int) cost;
            lease.localTokens.set(tokensAfterConsume);
            lease.lastLeaseSize = leaseSize;

            return Decision.allow(tokensAfterConsume, 0, buildHeaders(tokensAfterConsume, false));
        }
    }

    /**
     * Jittered lease size: base ± (base × jitterFraction).
     *
     * With base=50 and jitter=0.5, the lease size is uniform in [25, 75].
     * This desynchronizes lease fetches across pods, preventing the
     * "thundering herd" where all pods fetch simultaneously.
     */
    private int computeJitteredLeaseSize() {
        if (jitterFraction == 0) return baseLeaseSizeTokens;
        int jitterRange = (int) (baseLeaseSizeTokens * jitterFraction);
        int min = baseLeaseSizeTokens - jitterRange;
        int max = baseLeaseSizeTokens + jitterRange;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private Map<String, String> buildHeaders(long remaining, boolean fromCache) {
        return Map.of(
                "X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)),
                "X-RateLimit-Source", fromCache ? "local-cache" : "redis"
        );
    }

    // --- Metrics ---

    /** Returns the number of local cache hits (Redis calls avoided) for a key. */
    public long getLocalHits(String key) {
        LocalLease lease = leases.get(key);
        return lease != null ? lease.localHits.get() : 0;
    }

    /** Returns the number of Redis fetches for a key. */
    public long getRedisFetches(String key) {
        LocalLease lease = leases.get(key);
        return lease != null ? lease.redisFetches.get() : 0;
    }

    /** Returns the current local token count for a key. */
    public int getLocalTokens(String key) {
        LocalLease lease = leases.get(key);
        return lease != null ? lease.localTokens.get() : 0;
    }

    /** Returns the cache hit ratio for a key: localHits / (localHits + redisFetches). */
    public double getCacheHitRatio(String key) {
        LocalLease lease = leases.get(key);
        if (lease == null) return 0;
        long hits = lease.localHits.get();
        long fetches = lease.redisFetches.get();
        long total = hits + fetches;
        return total == 0 ? 0 : (double) hits / total;
    }

    /** Per-key local lease state. */
    private static class LocalLease {
        final AtomicInteger localTokens = new AtomicInteger(0);
        final AtomicLong localHits = new AtomicLong(0);
        final AtomicLong redisFetches = new AtomicLong(0);
        volatile int lastLeaseSize;
    }
}
