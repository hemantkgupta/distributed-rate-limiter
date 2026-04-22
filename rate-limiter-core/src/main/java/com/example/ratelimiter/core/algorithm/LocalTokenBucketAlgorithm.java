package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalTokenBucketAlgorithm implements LimitAlgorithm {
    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();
    private final long capacity;
    private final Duration refillPeriod;

    public LocalTokenBucketAlgorithm(long capacity, Duration refillPeriod) {
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        Bucket bucket = cache.computeIfAbsent(key, this::createNewBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(cost);

        Map<String, String> headers = Map.of(
            "X-RateLimit-Limit", String.valueOf(capacity),
            "X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens())
        );

        if (probe.isConsumed()) {
            return Decision.allow(probe.getRemainingTokens(), probe.getNanosToWaitForRefill() / 1_000_000, headers);
        } else {
            return Decision.deny(probe.getNanosToWaitForRefill() / 1_000_000, headers);
        }
    }

    private Bucket createNewBucket(String key) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, refillPeriod));
        return Bucket.builder().addLimit(limit).build();
    }
}
