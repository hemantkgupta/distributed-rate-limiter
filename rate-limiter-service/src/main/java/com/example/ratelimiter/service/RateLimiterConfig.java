package com.example.ratelimiter.service;

import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import com.example.ratelimiter.redis.RedisTokenBucketAlgorithm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RateLimiterConfig {

    @Bean
    public LimitAlgorithm limitAlgorithm(StringRedisTemplate redisTemplate, RedisScript<Long> tokenBucketScript) {
        // e.g., 100 requests capacity, refilling 10 requests per second
        return new RedisTokenBucketAlgorithm(redisTemplate, tokenBucketScript, 100, 10.0);
    }
}
