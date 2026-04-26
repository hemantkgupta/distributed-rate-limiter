package com.example.ratelimiter.core.config;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import com.example.ratelimiter.core.descriptor.LimitDescriptor;

import java.util.*;

/**
 * Evaluates a request against multiple rate limits simultaneously.
 *
 * A single request can match multiple limits. For example, an API call
 * from user "abc" to endpoint "/v1/charges" might match:
 *   1. A per-user limit: 1000 req/min for user "abc"
 *   2. A per-endpoint limit: 100 req/sec for "/v1/charges"
 *   3. A global limit: 10000 req/sec across all users
 *
 * ALL matched limits must allow the request. If ANY denies, the request
 * is denied. This is the AND semantics described in the blog.
 *
 * Evaluation order: most specific descriptor first (more entries = more specific).
 * This is deterministic — same set of limits always evaluates in the same order.
 * Deterministic ordering prevents subtle bugs where the evaluation order affects
 * which limit's tokens are consumed before a denial.
 *
 * Per-key atomicity: each individual limit is evaluated atomically (via Lua script
 * or local synchronization). Multi-key evaluation is best-effort: if limit 1 allows
 * and limit 2 denies, limit 1's tokens are already consumed. This is the documented
 * tradeoff — true multi-key atomicity would require distributed transactions.
 */
public class MultiLimitEvaluator {

    private final Map<String, LimitAlgorithm> algorithms;

    /**
     * @param algorithms map of limit name → algorithm instance
     */
    public MultiLimitEvaluator(Map<String, LimitAlgorithm> algorithms) {
        this.algorithms = new LinkedHashMap<>(algorithms);
    }

    /**
     * Evaluates a request against all matching limits.
     *
     * @param descriptors the limit descriptors for this request
     * @param domain      the Envoy rate limit domain
     * @param cost        the cost of this request (usually 1)
     * @return the most restrictive decision (denied if any limit denies)
     */
    public Decision evaluate(List<LimitDescriptor> descriptors, String domain, long cost) {
        if (descriptors.isEmpty()) {
            return Decision.allow(Long.MAX_VALUE, 0, Map.of());
        }

        // Sort by specificity: most specific first (deterministic ordering)
        List<LimitDescriptor> sorted = new ArrayList<>(descriptors);
        sorted.sort((a, b) -> Integer.compare(b.specificity(), a.specificity()));

        Decision mostRestrictive = null;
        long minRemaining = Long.MAX_VALUE;

        for (LimitDescriptor descriptor : sorted) {
            String redisKey = descriptor.toRedisKey(domain);

            // Find the matching algorithm for this descriptor
            LimitAlgorithm algorithm = findAlgorithm(descriptor);
            if (algorithm == null) continue; // No limit configured for this descriptor

            Decision decision = algorithm.checkAndConsume(redisKey, cost);

            if (!decision.allowed()) {
                // ANY denial → overall denial. Return immediately.
                return decision;
            }

            // Track the most restrictive "allow" (lowest remaining)
            if (decision.remaining() < minRemaining) {
                minRemaining = decision.remaining();
                mostRestrictive = decision;
            }
        }

        if (mostRestrictive == null) {
            return Decision.allow(Long.MAX_VALUE, 0, Map.of());
        }

        return mostRestrictive;
    }

    /**
     * Finds the algorithm configured for a descriptor.
     * Matches by the first entry's key (e.g., "api_key" → api_key algorithm).
     */
    private LimitAlgorithm findAlgorithm(LimitDescriptor descriptor) {
        // Try exact match on first entry key
        String firstKey = descriptor.entries().getFirst().key();
        LimitAlgorithm alg = algorithms.get(firstKey);
        if (alg != null) return alg;

        // Try "default" algorithm
        return algorithms.get("default");
    }
}
