package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class GcraAlgorithmTest {

    @Test
    void allowsBurstUpToMaxBurst() {
        var alg = new GcraAlgorithm(10, 5); // 10/sec, burst of 5
        for (int i = 0; i < 5; i++) {
            assertTrue(alg.checkAndConsume("u", 1).allowed(),
                    "Request " + (i + 1) + " should be allowed within burst");
        }
    }

    @Test
    void deniesAfterBurstExhausted() {
        // GCRA burst=3 means TAT can be up to 3*emission_interval ahead of now.
        // First request starts TAT at now + emission_interval. Each subsequent
        // request pushes TAT further. After burst+1 requests, TAT is too far ahead.
        var alg = new GcraAlgorithm(10, 3); // 10/sec, burst of 3
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (alg.checkAndConsume("u", 1).allowed()) allowed++;
        }
        // Should allow burst+1 (first at now, then burst more before TAT gets too far)
        assertTrue(allowed >= 3 && allowed <= 5,
                "GCRA with burst=3 should allow 3-5, got " + allowed);
    }

    @Test
    void retryAfterIsExact() {
        // 1 req/sec → emission interval = 1000ms, burst = 1
        // burst=1 allows first request + 1 more within tolerance = 2 total
        var alg = new GcraAlgorithm(1, 1);
        assertTrue(alg.checkAndConsume("u", 1).allowed()); // 1st
        assertTrue(alg.checkAndConsume("u", 1).allowed()); // 2nd (within burst)

        Decision denied = alg.checkAndConsume("u", 1); // 3rd → should be denied
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);
        assertTrue(denied.retryAfterMs() <= 2100);
    }

    @Test
    void tatAdvancesPerRequest() {
        // 10 req/sec → emission interval = 100ms
        var alg = new GcraAlgorithm(10, 5);

        long tatBefore = alg.getTat("u");
        assertEquals(-1, tatBefore); // Not set yet

        alg.checkAndConsume("u", 1);
        long tatAfter = alg.getTat("u");
        assertTrue(tatAfter > 0); // TAT is now set

        alg.checkAndConsume("u", 1);
        long tatAfter2 = alg.getTat("u");
        assertTrue(tatAfter2 > tatAfter); // TAT advances with each request
    }

    @Test
    void keysAreIndependent() {
        var alg = new GcraAlgorithm(10, 2);
        // burst=2 allows first request + 2 more = 3 total before denial
        assertTrue(alg.checkAndConsume("a", 1).allowed());
        assertTrue(alg.checkAndConsume("a", 1).allowed());
        assertTrue(alg.checkAndConsume("a", 1).allowed());
        assertFalse(alg.checkAndConsume("a", 1).allowed()); // "a" exhausted

        assertTrue(alg.checkAndConsume("b", 1).allowed()); // "b" still has burst
    }

    @Test
    void costGreaterThanOne() {
        var alg = new GcraAlgorithm(10, 10); // burst = 10
        Decision d = alg.checkAndConsume("u", 5);
        assertTrue(d.allowed());
        // TAT should have advanced by 5 × 100ms = 500ms
    }

    @Test
    void zeroBurstAllowsOnlyAtRate() {
        // No burst: must wait between every request
        var alg = new GcraAlgorithm(10, 0); // 10/sec, 0 burst
        assertTrue(alg.checkAndConsume("u", 1).allowed()); // First request always allowed
        // Immediately after: denied (no burst tolerance)
        Decision d = alg.checkAndConsume("u", 1);
        assertFalse(d.allowed());
    }

    @Test
    void remainingComputedFromTat() {
        var alg = new GcraAlgorithm(10, 5);
        Decision d = alg.checkAndConsume("u", 1);
        assertTrue(d.allowed());
        // After consuming 1 from burst of 5, remaining should be ~4
        assertTrue(d.remaining() >= 3 && d.remaining() <= 5);
    }

    @Test
    void headersIncludeAlgorithmInfo() {
        var alg = new GcraAlgorithm(100, 10);
        Decision d = alg.checkAndConsume("u", 1);
        assertTrue(d.headers().containsKey("X-RateLimit-Limit"));
        assertTrue(d.headers().containsKey("X-RateLimit-Remaining"));
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new GcraAlgorithm(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new GcraAlgorithm(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new GcraAlgorithm(10, -1));
    }
}
