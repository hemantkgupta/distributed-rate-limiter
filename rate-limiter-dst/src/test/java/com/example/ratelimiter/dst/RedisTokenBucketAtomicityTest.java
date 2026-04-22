package com.example.ratelimiter.dst;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.redis.RedisTokenBucketAlgorithm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = {com.example.ratelimiter.service.RateLimitApplication.class})
@Testcontainers
public class RedisTokenBucketAtomicityTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    public void testAtomicityDoubleBurstRaceCondition() throws InterruptedException {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(Long.class);

        // Capacity of exactly 10, extremely slow refill
        RedisTokenBucketAlgorithm algorithm = new RedisTokenBucketAlgorithm(redisTemplate, script, 10, 0.0001);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(threads);

        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    Decision decision = algorithm.checkAndConsume("test_concurrency", 1);
                    if (decision.allowed()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    complete.countDown();
                }
            });
        }

        // Release all threads simultaneously
        latch.countDown();
        complete.await();

        // ONLY 10 requests should be allowed despite 100 concurrent threads hitting Redis at the same millisecond
        // Validating the Lua script acts as a perfect concurrency lockdown.
        assertEquals(10, allowedCount.get());
    }
}
