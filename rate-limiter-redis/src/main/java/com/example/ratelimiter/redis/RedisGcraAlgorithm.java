package com.example.ratelimiter.redis;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public class RedisGcraAlgorithm implements LimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> gcraScript;
    // Emission Interval: Rate of token drip (e.g. 100/s -> 10ms per token)
    private final long emissionIntervalMs; 
    // Burst Tolerance: How many tokens we can burst (e.g. 5 tokens = 50ms)
    private final long burstToleranceMs;

    public RedisGcraAlgorithm(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> gcraScript,
            long ratePerSecond,
            long maxBurst) {
        this.redisTemplate = redisTemplate;
        this.gcraScript = gcraScript;
        this.emissionIntervalMs = 1000 / ratePerSecond;
        this.burstToleranceMs = maxBurst * this.emissionIntervalMs;
    }

    @Override
    public Decision checkAndConsume(String key, long cost) {
        long nowMs = Instant.now().toEpochMilli();
        
        Long retryAfterMs = redisTemplate.execute(
            gcraScript,
            Collections.singletonList("rls:{" + key + "}:gcra"),
            String.valueOf(cost),
            String.valueOf(emissionIntervalMs),
            String.valueOf(burstToleranceMs),
            String.valueOf(nowMs)
        );

        Map<String, String> headers = Map.of(
            "X-RateLimit-Algorithm", "GCRA"
        );

        if (retryAfterMs != null && retryAfterMs == 0) {
            // Request allowed!
            return Decision.allow(0, 0, headers); // We could compute remaining based on TAT delta
        } else {
            // Denied! retryAfterMs tells us exactly when we can retry
            return Decision.deny(retryAfterMs != null ? retryAfterMs : 1000, headers);
        }
    }
}
