package com.example.ratelimiter.core.descriptor;

import java.util.List;
import java.util.Objects;

/**
 * A compound rate limit descriptor matching Envoy's descriptor model.
 *
 * Envoy sends rate limit requests with descriptors — ordered lists of
 * key-value pairs that identify the resource being limited. Examples:
 *
 *   [("remote_address", "1.2.3.4")]
 *   [("api_key", "sk_live_abc123")]
 *   [("api_key", "sk_live_abc123"), ("endpoint", "/v1/charges")]
 *
 * Each descriptor maps to a limit definition (rate, capacity, algorithm).
 * Descriptors are hierarchical: a request can match multiple limits
 * simultaneously (e.g., both an IP limit AND an API key limit).
 *
 * Multi-limit evaluation order (Blog Part 5):
 * When a request matches multiple limits, ALL must pass. Limits are
 * evaluated in deterministic order (by descriptor specificity, most
 * specific first). If ANY limit denies, the request is denied.
 * This prevents a user from bypassing a per-endpoint limit by not
 * exceeding the per-user limit.
 */
public record LimitDescriptor(List<Entry> entries) {

    /**
     * A single key-value pair in the descriptor.
     * Examples: ("remote_address", "1.2.3.4"), ("api_key", "sk_live_abc123")
     */
    public record Entry(String key, String value) {
        public Entry {
            Objects.requireNonNull(key, "descriptor key must not be null");
            Objects.requireNonNull(value, "descriptor value must not be null");
        }
    }

    public LimitDescriptor {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("descriptor must have at least one entry");
        }
    }

    /**
     * Returns the compound key for this descriptor.
     * Used as the Redis key for the rate limit state.
     *
     * Format: "rls:{domain}:{key1}:{value1}:{key2}:{value2}:..."
     *
     * The key is deterministic: same entries always produce the same key.
     */
    public String toRedisKey(String domain) {
        StringBuilder sb = new StringBuilder("rls:");
        sb.append(domain);
        for (Entry entry : entries) {
            sb.append(':').append(entry.key()).append(':').append(entry.value());
        }
        return sb.toString();
    }

    /**
     * Returns the number of entries (specificity level).
     * More entries = more specific descriptor.
     */
    public int specificity() {
        return entries.size();
    }

    /**
     * Returns true if this descriptor is more specific than the other.
     * Used for deterministic multi-limit evaluation ordering.
     */
    public boolean isMoreSpecificThan(LimitDescriptor other) {
        return this.specificity() > other.specificity();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(entries.get(i).key()).append("=").append(entries.get(i).value());
        }
        sb.append("]");
        return sb.toString();
    }
}
