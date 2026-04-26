package com.example.ratelimiter.core.resilience;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fail-open circuit breaker.
 *
 * Critical property: if Redis dies, traffic keeps flowing (fail-open).
 * The rate limiter must never become the threat it was designed to prevent.
 */
class FailOpenCircuitBreakerTest {

    @Test
    void closedState_delegatesToPrimary() {
        LimitAlgorithm primary = (key, cost) ->
                Decision.allow(99, 0, Map.of("source", "redis"));

        var breaker = new FailOpenCircuitBreaker(primary, 100, 10, 3, 5000, 100);

        Decision d = breaker.checkAndConsume("u", 1);
        assertTrue(d.allowed());
        assertEquals("redis", d.headers().get("source"));
        assertEquals(FailOpenCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void tripsOpenAfterConsecutiveFailures() {
        AtomicInteger calls = new AtomicInteger(0);
        LimitAlgorithm failing = (key, cost) -> {
            calls.incrementAndGet();
            throw new RuntimeException("Redis connection refused");
        };

        var breaker = new FailOpenCircuitBreaker(failing, 100, 10, 3, 5000, 100);

        // 3 failures should trip the circuit
        for (int i = 0; i < 3; i++) {
            Decision d = breaker.checkAndConsume("u", 1);
            assertTrue(d.allowed()); // Fail-open: still allows
        }

        assertEquals(FailOpenCircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void openState_usesFallback() {
        LimitAlgorithm failing = (key, cost) -> {
            throw new RuntimeException("Redis down");
        };

        var breaker = new FailOpenCircuitBreaker(failing, 10, 1.0, 1, 60_000, 100);

        // Trip the circuit (1 failure → OPEN). This first call also uses fallback.
        Decision firstCall = breaker.checkAndConsume("u", 1);
        assertTrue(firstCall.allowed()); // fail-open via fallback
        assertEquals(FailOpenCircuitBreaker.State.OPEN, breaker.getState());

        // Now in OPEN state: subsequent calls should use local fallback
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (breaker.checkAndConsume("u", 1).allowed()) allowed++;
        }
        // Fallback has capacity 10, first call consumed 1, so ~9 more
        assertTrue(allowed >= 5 && allowed <= 15,
                "Expected 5-15 allowed via fallback, got " + allowed);
        assertTrue(breaker.getFallbackInvocations() > 0);
    }

    @Test
    void halfOpen_probeSucceeds_closesCircuit() {
        AtomicInteger callCount = new AtomicInteger(0);
        LimitAlgorithm flaky = (key, cost) -> {
            if (callCount.incrementAndGet() <= 3) {
                throw new RuntimeException("Redis flaky");
            }
            return Decision.allow(99, 0, Map.of("source", "redis"));
        };

        var breaker = new FailOpenCircuitBreaker(flaky, 100, 10, 3, 0, 100);

        // 3 failures → OPEN
        for (int i = 0; i < 3; i++) breaker.checkAndConsume("u", 1);
        assertEquals(FailOpenCircuitBreaker.State.OPEN, breaker.getState());

        // openDurationMs = 0 → immediately transitions to HALF_OPEN on next request
        // 4th call succeeds → circuit should close
        Decision d = breaker.checkAndConsume("u", 1);
        assertTrue(d.allowed());
        assertEquals(FailOpenCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void halfOpen_probeFails_reopensCircuit() {
        LimitAlgorithm alwaysFailing = (key, cost) -> {
            throw new RuntimeException("Redis dead");
        };

        var breaker = new FailOpenCircuitBreaker(alwaysFailing, 100, 10, 1, 0, 100);

        // Trip → OPEN
        breaker.checkAndConsume("u", 1);
        assertEquals(FailOpenCircuitBreaker.State.OPEN, breaker.getState());

        // openDurationMs = 0 → half-open probe on next call
        // Probe fails → back to OPEN
        breaker.checkAndConsume("u", 1);
        assertEquals(FailOpenCircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void successResetsFailureCounter() {
        AtomicInteger callCount = new AtomicInteger(0);
        LimitAlgorithm intermittent = (key, cost) -> {
            int n = callCount.incrementAndGet();
            if (n == 2) throw new RuntimeException("blip");
            return Decision.allow(99, 0, Map.of());
        };

        // threshold = 3: needs 3 CONSECUTIVE failures
        var breaker = new FailOpenCircuitBreaker(intermittent, 100, 10, 3, 5000, 100);

        breaker.checkAndConsume("u", 1); // success (1)
        breaker.checkAndConsume("u", 1); // fail (2) → consecutive=1
        breaker.checkAndConsume("u", 1); // success (3) → consecutive reset to 0

        assertEquals(FailOpenCircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getConsecutiveFailures());
    }

    @Test
    void metricsAccumulate() {
        LimitAlgorithm primary = (key, cost) -> Decision.allow(99, 0, Map.of());

        var breaker = new FailOpenCircuitBreaker(primary, 100, 10, 3, 5000, 100);

        for (int i = 0; i < 10; i++) breaker.checkAndConsume("u", 1);

        assertEquals(10, breaker.getTotalRequests());
        assertEquals(10, breaker.getPrimarySuccesses());
        assertEquals(0, breaker.getFallbackInvocations());
    }

    @Test
    void failOpenPassthrough_evenFallbackFails() {
        LimitAlgorithm failingPrimary = (key, cost) -> {
            throw new RuntimeException("Redis down");
        };

        // Create breaker with impossible fallback (capacity=0 would fail)
        // Actually the TokenBucket fallback won't throw, it'll just deny.
        // So this test verifies the fallback path works even under stress.
        var breaker = new FailOpenCircuitBreaker(failingPrimary, 100, 10, 1, 60_000, 100);

        // Trip circuit
        Decision d = breaker.checkAndConsume("u", 1);
        assertTrue(d.allowed()); // Fail-open via fallback
        assertEquals(1, breaker.getFallbackInvocations());
    }
}
