package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeakyBucketAlgorithmTest {

    @Test
    void allowsWhenBucketHasCapacity() {
        var alg = new LeakyBucketAlgorithm(10, 5.0); // capacity 10, leak 5/sec
        Decision d = alg.checkAndConsume("u", 1);
        assertTrue(d.allowed());
        assertEquals(9, d.remaining());
    }

    @Test
    void fillsUpToCapacity() {
        var alg = new LeakyBucketAlgorithm(5, 1.0);
        for (int i = 0; i < 5; i++) assertTrue(alg.checkAndConsume("u", 1).allowed());
        assertFalse(alg.checkAndConsume("u", 1).allowed());
    }

    @Test
    void deniedResponseHasRetryAfter() {
        var alg = new LeakyBucketAlgorithm(1, 1.0); // capacity 1, leak 1/sec
        alg.checkAndConsume("u", 1); // Fill to capacity
        Decision denied = alg.checkAndConsume("u", 1);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);
    }

    @Test
    void keysAreIndependent() {
        var alg = new LeakyBucketAlgorithm(2, 1.0);
        alg.checkAndConsume("a", 1);
        alg.checkAndConsume("a", 1);
        assertFalse(alg.checkAndConsume("a", 1).allowed());
        assertTrue(alg.checkAndConsume("b", 1).allowed());
    }

    @Test
    void costGreaterThanOne() {
        var alg = new LeakyBucketAlgorithm(10, 5.0);
        Decision d = alg.checkAndConsume("u", 4);
        assertTrue(d.allowed());
        assertEquals(6, d.remaining());
    }

    @Test
    void costExceedingCapacityIsDenied() {
        var alg = new LeakyBucketAlgorithm(5, 1.0);
        assertFalse(alg.checkAndConsume("u", 6).allowed());
    }

    @Test
    void headersPresentOnAllow() {
        var alg = new LeakyBucketAlgorithm(100, 10.0);
        Decision d = alg.checkAndConsume("u", 1);
        assertEquals("100", d.headers().get("X-RateLimit-Limit"));
        assertEquals("99", d.headers().get("X-RateLimit-Remaining"));
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new LeakyBucketAlgorithm(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new LeakyBucketAlgorithm(10, 0));
    }
}
