package com.example.ratelimiter.dst;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.redis.RedisGcraAlgorithm;
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
public class RedisGcraAtomicityTest {

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
    public void testAtomicityGcraDoubleBurstRaceCondition() throws InterruptedException {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/gcra.lua"));
        script.setResultType(Long.class);

        // 1 request per second, burst tolerance of 5
        // This means it should allow exactly 5 requests instantly, and reject the rest
        RedisGcraAlgorithm algorithm = new RedisGcraAlgorithm(redisTemplate, script, 1, 5);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(threads);

        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // wait until released
                    Decision decision = algorithm.checkAndConsume("test_concurrency_gcra", 1);
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

        // Exactly 5 should be permitted due to the burst budget limits under atomicity
        assertEquals(5, allowedCount.get());
    }
}
