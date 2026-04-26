package com.example.ratelimiter.core.config;

import java.util.Objects;

/**
 * Defines a rate limit rule: which descriptor pattern to match,
 * which algorithm to use, and what the limit parameters are.
 *
 * Examples:
 *   IP limit:       descriptor=("remote_address", "*"), algorithm=TOKEN_BUCKET, capacity=100, rate=10/s
 *   API key limit:  descriptor=("api_key", "*"), algorithm=GCRA, rate=1000/s, burst=100
 *   Endpoint limit: descriptor=("api_key", "*", "endpoint", "/v1/charges"), algorithm=TOKEN_BUCKET, capacity=50, rate=5/s
 *
 * Definitions are loaded from configuration (YAML, database, etc.)
 * and matched against incoming request descriptors.
 */
public record LimitDefinition(
        String name,
        String descriptorPattern,  // e.g., "api_key:*" or "remote_address:*:endpoint:/v1/charges"
        AlgorithmType algorithm,
        long capacity,             // max requests (token bucket capacity, or GCRA burst)
        double ratePerSecond,      // sustained rate
        boolean enabled
) {
    public enum AlgorithmType {
        FIXED_WINDOW,
        SLIDING_WINDOW_COUNTER,
        TOKEN_BUCKET,
        LEAKY_BUCKET,
        GCRA
    }

    public LimitDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be positive");
    }

    /**
     * Returns the window size in milliseconds for window-based algorithms.
     * Derived from capacity / ratePerSecond.
     */
    public long windowSizeMs() {
        return (long) (capacity / ratePerSecond * 1000);
    }
}
