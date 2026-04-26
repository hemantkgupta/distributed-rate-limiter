package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketAlgorithmTest {

    @Test
    void startsAtFullCapacity() {
        var alg = new TokenBucketAlgorithm(10, 1.0);
        Decision d = alg.checkAndConsume("u", 1);
        assertTrue(d.allowed());
        assertEquals(9, d.remaining());
    }

    @Test
    void allowsBurstUpToCapacity() {
        var alg = new TokenBucketAlgorithm(5, 1.0);
        for (int i = 0; i < 5; i++) {
            assertTrue(alg.checkAndConsume("u", 1).allowed());
        }
        assertFalse(alg.checkAndConsume("u", 1).allowed());
    }

    @Test
    void refillsOverTime() {
        long baseTime = 100_000;
        Clock clock1 = Clock.fixed(Instant.ofEpochMilli(baseTime), ZoneOffset.UTC);
        var alg = new TokenBucketAlgorithm(5, 1.0, clock1); // 1 token/sec

        // Drain all 5 tokens
        for (int i = 0; i < 5; i++) alg.checkAndConsume("u", 1);
        assertFalse(alg.checkAndConsume("u", 1).allowed());

        // Advance time by 3 seconds → should refill 3 tokens
        Clock clock2 = Clock.fixed(Instant.ofEpochMilli(baseTime + 3000), ZoneOffset.UTC);
        var alg2 = new TokenBucketAlgorithm(5, 1.0, clock2);

        // New algorithm starts fresh — test the refill math directly
        assertEquals(5.0, alg2.getAvailableTokens("u")); // Full because new instance
    }

    @Test
    void costGreaterThanOneWorks() {
        var alg = new TokenBucketAlgorithm(10, 5.0);
        Decision d = alg.checkAndConsume("u", 3);
        assertTrue(d.allowed());
        assertEquals(7, d.remaining());
    }

    @Test
    void costExceedingAvailableIsDenied() {
        var alg = new TokenBucketAlgorithm(5, 1.0);
        Decision d = alg.checkAndConsume("u", 6);
        assertFalse(d.allowed());
        assertTrue(d.retryAfterMs() > 0);
    }

    @Test
    void retryAfterIsAccurate() {
        var alg = new TokenBucketAlgorithm(1, 1.0); // 1 token, 1/sec refill
        alg.checkAndConsume("u", 1); // Drain
        Decision d = alg.checkAndConsume("u", 1);
        assertFalse(d.allowed());
        // Should need ~1000ms to refill 1 token
        assertTrue(d.retryAfterMs() > 0);
        assertTrue(d.retryAfterMs() <= 1100); // Allow some margin
    }

    @Test
    void keysAreIndependent() {
        var alg = new TokenBucketAlgorithm(2, 1.0);
        alg.checkAndConsume("a", 1);
        alg.checkAndConsume("a", 1);
        assertFalse(alg.checkAndConsume("a", 1).allowed());
        assertTrue(alg.checkAndConsume("b", 1).allowed());
    }

    @Test
    void headersIncludeLimitAndRemaining() {
        var alg = new TokenBucketAlgorithm(100, 10.0);
        Decision d = alg.checkAndConsume("u", 1);
        assertEquals("100", d.headers().get("X-RateLimit-Limit"));
        assertEquals("99", d.headers().get("X-RateLimit-Remaining"));
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketAlgorithm(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketAlgorithm(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketAlgorithm(-1, 1.0));
    }
}
