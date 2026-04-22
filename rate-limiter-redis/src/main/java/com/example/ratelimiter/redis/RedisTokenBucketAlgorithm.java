package com.example.ratelimiter.redis;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public class RedisTokenBucketAlgorithm implements LimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> tokenBucketScript;
    private final long capacity;
    private final double refillRatePerSecond;

    public RedisTokenBucketAlgorithm(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> tokenBucketScript,
            long capacity,
            double refillRatePerSecond) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long now = Instant.now().getEpochSecond();
        
        Long result = redisTemplate.execute(
            tokenBucketScript,
            Collections.singletonList("rls:{" + key + "}:tb"),
            String.valueOf(cost),
            String.valueOf(capacity),
            String.valueOf(refillRatePerSecond),
            String.valueOf(now)
        );

        Map<String, String> headers = Map.of(
            "X-RateLimit-Limit", String.valueOf(capacity)
        );

        if (result != null && result >= 0) {
            return Decision.allow(result, 0, headers);
        } else {
            // Need to calculate Retry-After based on capacity and refill rate in a real system
            return Decision.deny(1000, headers);
        }
    }
}
