package com.example.ratelimiter.core.algorithm;

import com.example.ratelimiter.core.Decision;

public interface LimitAlgorithm {
    /**
     * Checks if a request is allowed and consumes the tokens if it is.
     * @param key The unique identifier for the limit (e.g. user123)
     * @param cost The cost of the request (usually 1)
     * @return Decision indicating whether the request is allowed
     */
    Decision checkAndConsume(String key, long cost);
}
