package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowAlgorithmTest {

    @Test
    void allowsRequestsWithinLimit() {
        var alg = new FixedWindowAlgorithm(5, 60_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(alg.checkAndConsume("user1", 1).allowed());
        }
    }

    @Test
    void deniesWhenLimitExceeded() {
        var alg = new FixedWindowAlgorithm(3, 60_000);
        assertTrue(alg.checkAndConsume("user1", 1).allowed());
        assertTrue(alg.checkAndConsume("user1", 1).allowed());
        assertTrue(alg.checkAndConsume("user1", 1).allowed());
        assertFalse(alg.checkAndConsume("user1", 1).allowed());
    }

    @Test
    void remainingDecrementsCorrectly() {
        var alg = new FixedWindowAlgorithm(10, 60_000);
        assertEquals(9, alg.checkAndConsume("u", 1).remaining());
        assertEquals(8, alg.checkAndConsume("u", 1).remaining());
        assertEquals(7, alg.checkAndConsume("u", 1).remaining());
    }

    @Test
    void separateKeysAreIndependent() {
        var alg = new FixedWindowAlgorithm(2, 60_000);
        assertTrue(alg.checkAndConsume("a", 1).allowed());
        assertTrue(alg.checkAndConsume("a", 1).allowed());
        assertFalse(alg.checkAndConsume("a", 1).allowed());
        // "b" should still have its full limit
        assertTrue(alg.checkAndConsume("b", 1).allowed());
        assertTrue(alg.checkAndConsume("b", 1).allowed());
    }

    @Test
    void costGreaterThanOneConsumesMultipleTokens() {
        var alg = new FixedWindowAlgorithm(10, 60_000);
        Decision d = alg.checkAndConsume("u", 5);
        assertTrue(d.allowed());
        assertEquals(5, d.remaining());
    }

    @Test
    void costExceedingRemainingIsDenied() {
        var alg = new FixedWindowAlgorithm(5, 60_000);
        alg.checkAndConsume("u", 3);
        assertFalse(alg.checkAndConsume("u", 5).allowed());
    }

    @Test
    void windowResetsAfterExpiry() {
        // Use a fixed clock that we control
        long windowMs = 1000;
        long baseTime = 100_000;
        Clock clock = Clock.fixed(Instant.ofEpochMilli(baseTime), ZoneOffset.UTC);
        var alg = new FixedWindowAlgorithm(2, windowMs, clock);

        assertTrue(alg.checkAndConsume("u", 1).allowed());
        assertTrue(alg.checkAndConsume("u", 1).allowed());
        assertFalse(alg.checkAndConsume("u", 1).allowed());

        // Move clock past window boundary
        Clock newClock = Clock.fixed(Instant.ofEpochMilli(baseTime + windowMs + 1), ZoneOffset.UTC);
        var alg2 = new FixedWindowAlgorithm(2, windowMs, newClock);
        assertTrue(alg2.checkAndConsume("u", 1).allowed());
    }

    @Test
    void deniedResponseIncludesRetryAfter() {
        var alg = new FixedWindowAlgorithm(1, 60_000);
        alg.checkAndConsume("u", 1);
        Decision denied = alg.checkAndConsume("u", 1);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);
        assertTrue(denied.retryAfterMs() <= 60_000);
    }

    @Test
    void headersArePopulated() {
        var alg = new FixedWindowAlgorithm(100, 60_000);
        Decision d = alg.checkAndConsume("u", 1);
        assertEquals("100", d.headers().get("X-RateLimit-Limit"));
        assertEquals("99", d.headers().get("X-RateLimit-Remaining"));
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowAlgorithm(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowAlgorithm(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowAlgorithm(-1, 1000));
    }
}
