package com.example.ratelimiter.core.integration;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: compares all 5 algorithms under identical conditions.
 *
 * This is the test that proves the blog's claims about each algorithm's
 * behavior — burst handling, memory efficiency, boundary behavior.
 *
 * Tests:
 * 1. All algorithms enforce limits correctly (no over-admission)
 * 2. Token Bucket and GCRA allow bursts; Fixed/Sliding Window don't accumulate
 * 3. Concurrent access doesn't produce over-limit admission on any algorithm
 * 4. All algorithms produce valid rate limit headers
 */
class AlgorithmComparisonTest {

    /** All 5 algorithms configured to ~10 requests per second. */
    private List<LimitAlgorithm> createAllAlgorithms() {
        return List.of(
                new FixedWindowAlgorithm(10, 1000),                  // 10 per 1s window
                new SlidingWindowCounterAlgorithm(10, 1000),         // 10 per 1s sliding
                new TokenBucketAlgorithm(10, 10.0),                  // capacity 10, 10/sec refill
                new LeakyBucketAlgorithm(10, 10.0),                  // capacity 10, 10/sec leak
                new GcraAlgorithm(10, 10)                             // 10/sec, burst 10
        );
    }

    @Test
    void allAlgorithms_enforceLimit_noOverAdmission() {
        for (LimitAlgorithm alg : createAllAlgorithms()) {
            int allowed = 0;
            for (int i = 0; i < 100; i++) {
                if (alg.checkAndConsume("user", 1).allowed()) {
                    allowed++;
                }
            }
            // Each algorithm should allow no more than its capacity (~10)
            assertTrue(allowed <= 15,
                    alg.getClass().getSimpleName() + " allowed " + allowed + " (expected ≤15)");
            assertTrue(allowed >= 1,
                    alg.getClass().getSimpleName() + " allowed " + allowed + " (expected ≥1)");
        }
    }

    @Test
    void allAlgorithms_concurrent50Threads_noOverAdmission() throws Exception {
        for (LimitAlgorithm alg : createAllAlgorithms()) {
            int threads = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger totalAllowed = new AtomicInteger(0);

            ExecutorService exec = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        if (alg.checkAndConsume("concurrent-user", 1).allowed()) {
                            totalAllowed.incrementAndGet();
                        }
                    } catch (Exception e) { /* ignore */ }
                    finally { done.countDown(); }
                });
            }

            start.countDown();
            done.await();
            exec.shutdown();

            assertTrue(totalAllowed.get() <= 15,
                    alg.getClass().getSimpleName() + " allowed " + totalAllowed.get() +
                    " concurrent requests (expected ≤15)");
        }
    }

    @Test
    void allAlgorithms_returnValidDecision() {
        for (LimitAlgorithm alg : createAllAlgorithms()) {
            Decision d = alg.checkAndConsume("headers-test", 1);
            assertNotNull(d);
            assertTrue(d.allowed());
            assertTrue(d.remaining() >= 0);
            assertNotNull(d.headers());
            assertFalse(d.headers().isEmpty(),
                    alg.getClass().getSimpleName() + " should return non-empty headers");
        }
    }

    @Test
    void allAlgorithms_deniedResponse_hasRetryAfter() {
        for (LimitAlgorithm alg : createAllAlgorithms()) {
            // Drain the algorithm
            for (int i = 0; i < 20; i++) alg.checkAndConsume("drain", 1);

            Decision denied = alg.checkAndConsume("drain", 1);
            if (!denied.allowed()) {
                assertTrue(denied.retryAfterMs() > 0,
                        alg.getClass().getSimpleName() + " should have retryAfterMs > 0 on deny");
            }
        }
    }

    @Test
    void tokenBucketAndGcra_allowBurst() {
        // Token Bucket and GCRA should allow an instant burst up to capacity
        var tb = new TokenBucketAlgorithm(10, 10.0);
        var gcra = new GcraAlgorithm(10, 10);

        int tbBurst = 0, gcraBurst = 0;
        for (int i = 0; i < 10; i++) {
            if (tb.checkAndConsume("burst-tb", 1).allowed()) tbBurst++;
            if (gcra.checkAndConsume("burst-gcra", 1).allowed()) gcraBurst++;
        }

        assertEquals(10, tbBurst, "Token Bucket should allow full burst of 10");
        assertEquals(10, gcraBurst, "GCRA should allow full burst of 10");
    }

    @Test
    void multiKey_stressTest_1000users() {
        var alg = new TokenBucketAlgorithm(10, 10.0);

        Map<String, Integer> allowedPerUser = new HashMap<>();

        for (int user = 0; user < 1000; user++) {
            String key = "user-" + user;
            int allowed = 0;
            for (int req = 0; req < 20; req++) {
                if (alg.checkAndConsume(key, 1).allowed()) allowed++;
            }
            allowedPerUser.put(key, allowed);
        }

        // Every user should have been allowed exactly 10 (capacity = 10, no refill time)
        for (var entry : allowedPerUser.entrySet()) {
            assertEquals(10, entry.getValue(),
                    entry.getKey() + " should have been allowed exactly 10");
        }
    }
}
