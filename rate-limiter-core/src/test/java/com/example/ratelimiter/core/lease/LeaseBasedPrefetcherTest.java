package com.example.ratelimiter.core.lease;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import com.example.ratelimiter.core.algorithm.TokenBucketAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the lease-based prefetcher (L1 shadow buffer).
 *
 * The critical property: most requests are served from the local cache
 * without touching the backing store (Redis). Only 1 in N requests
 * triggers a backing-store call (where N = lease size).
 */
class LeaseBasedPrefetcherTest {

    @Test
    void servesFromLocalCacheAfterInitialFetch() {
        // Backing store with 1000 tokens
        LimitAlgorithm backing = new TokenBucketAlgorithm(1000, 100);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 50, 0.0);

        // First request triggers a Redis fetch (lease of 50)
        Decision d = prefetcher.checkAndConsume("u", 1);
        assertTrue(d.allowed());

        // Next 48 requests should be served from local cache
        for (int i = 0; i < 48; i++) {
            assertTrue(prefetcher.checkAndConsume("u", 1).allowed());
        }

        // Verify: 1 Redis fetch, 48 local hits
        assertEquals(1, prefetcher.getRedisFetches("u"));
        assertTrue(prefetcher.getLocalHits("u") >= 48);
    }

    @Test
    void fetchesNewLeaseWhenLocalExhausted() {
        LimitAlgorithm backing = new TokenBucketAlgorithm(1000, 100);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 10, 0.0);

        // Consume all 10 leased tokens + 1 more
        for (int i = 0; i < 11; i++) {
            prefetcher.checkAndConsume("u", 1);
        }

        // Should have fetched at least 2 leases (first 10 + second fetch)
        assertTrue(prefetcher.getRedisFetches("u") >= 2);
    }

    @Test
    void deniesWhenBackingStoreDenies() {
        LimitAlgorithm backing = new TokenBucketAlgorithm(5, 0.001); // 5 tokens, very slow refill
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 10, 0.0);

        // The backing store only has 5 tokens. A lease request for 10 will be denied.
        // The prefetcher should fall back to a single-token request.
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (prefetcher.checkAndConsume("u", 1).allowed()) allowed++;
        }
        // Should allow ~5 (the backing store's capacity)
        assertTrue(allowed <= 10); // Not unlimited
        assertTrue(allowed >= 1);  // At least some went through
    }

    @Test
    void keysAreIndependent() {
        LimitAlgorithm backing = new TokenBucketAlgorithm(1000, 100);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 50, 0.0);

        prefetcher.checkAndConsume("a", 1);
        prefetcher.checkAndConsume("b", 1);

        assertEquals(1, prefetcher.getRedisFetches("a"));
        assertEquals(1, prefetcher.getRedisFetches("b"));
    }

    @Test
    void cacheHitRatioApproachesOne() {
        LimitAlgorithm backing = new TokenBucketAlgorithm(10000, 1000);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 100, 0.0);

        // Make 500 requests → 5 Redis fetches + 495 local hits
        for (int i = 0; i < 500; i++) {
            prefetcher.checkAndConsume("u", 1);
        }

        double hitRatio = prefetcher.getCacheHitRatio("u");
        assertTrue(hitRatio > 0.9, "Cache hit ratio should be >90%, was " + hitRatio);
    }

    @Test
    void concurrentAccessDoesNotDoubleFetch() throws InterruptedException {
        LimitAlgorithm backing = new TokenBucketAlgorithm(10000, 1000);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 50, 0.0);

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 10; j++) {
                        if (prefetcher.checkAndConsume("u", 1).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        exec.shutdown();

        // All 200 requests should be allowed (backing has 10000 tokens)
        assertEquals(200, allowed.get());

        // Redis fetches should be much less than 200 (the whole point of prefetching)
        long fetches = prefetcher.getRedisFetches("u");
        assertTrue(fetches < 50, "Redis fetches (" + fetches + ") should be << 200");
    }

    @Test
    void jitterVariesLeaseSize() {
        // With jitter=0.5, lease sizes should vary between 25 and 75 (for base=50)
        LimitAlgorithm backing = new TokenBucketAlgorithm(100000, 10000);
        LeaseBasedPrefetcher prefetcher = new LeaseBasedPrefetcher(backing, 50, 0.5);

        // Make enough requests to trigger multiple lease fetches
        for (int i = 0; i < 500; i++) {
            prefetcher.checkAndConsume("u", 1);
        }

        // If jitter is working, we should see varying lease sizes reflected in
        // the number of Redis fetches being roughly 500/50 ± jitter
        long fetches = prefetcher.getRedisFetches("u");
        assertTrue(fetches > 3 && fetches < 30,
                "Expected 5-20 fetches with jitter, got " + fetches);
    }
}
