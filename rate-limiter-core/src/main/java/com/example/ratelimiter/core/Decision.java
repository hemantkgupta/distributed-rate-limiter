package com.example.ratelimiter.core;

import java.util.Map;

public record Decision(
    boolean allowed,
    long remaining,
    long resetAfterMs,
    long retryAfterMs,
    Map<String, String> headers
) {
    public static Decision allow(long remaining, long resetAfterMs, Map<String, String> headers) {
        return new Decision(true, remaining, resetAfterMs, 0, headers);
    }

    public static Decision deny(long retryAfterMs, Map<String, String> headers) {
        return new Decision(false, 0, 0, retryAfterMs, headers);
    }
}
