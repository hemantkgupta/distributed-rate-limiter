# Distributed Rate Limiter — Code Companion

Maps every blog part to the source files that implement it.

## Sync Rule

> **When the blog claims a mechanism exists, this companion must point to the file or test that implements it.**

## Blog Part → Code Map

| Blog Part | Code Location | Status |
|---|---|---|
| Part 1: The Problem | N/A — architecture only | Context |
| Part 2: Requirements | `core/config/LimitDefinition.java` | Limit parameters modeled |
| Part 3: Fixed Window | `core/algorithm/FixedWindowAlgorithm.java` | Implemented with boundary burst flaw demonstrated |
| Part 3: Sliding Window Log | N/A | Not implemented (O(N) memory — rejected by design) |
| Part 3: Token Bucket | `core/algorithm/TokenBucketAlgorithm.java`, `redis/RedisTokenBucketAlgorithm.java`, `scripts/token_bucket.lua` | Implemented (local + Redis) |
| Part 3: Leaky Bucket | `core/algorithm/LeakyBucketAlgorithm.java` | Implemented (efficient lazy form) |
| Part 3: GCRA | `core/algorithm/GcraAlgorithm.java`, `redis/RedisGcraAlgorithm.java`, `scripts/gcra.lua` | Implemented (local + Redis) |
| Part 3: Sliding Window Counter | `core/algorithm/SlidingWindowCounterAlgorithm.java` | Implemented (weighted approximation) |
| Part 4: 3-Tier Architecture | `service/grpc/EnvoyRateLimitGrpcService.java`, `infra/envoy/envoy.yaml` | Implemented (Envoy → gRPC → Redis) |
| Part 5: Lua Atomicity | `scripts/token_bucket.lua`, `scripts/gcra.lua` | Implemented (lock-free atomic check-and-consume) |
| Part 5: Race Condition Prevention | `dst/RedisTokenBucketAtomicityTest.java`, `dst/RedisGcraAtomicityTest.java` | Tested (100-thread concurrent burst) |
| Part 6: Lease-Based Prefetching | `core/lease/LeaseBasedPrefetcher.java` | Implemented (L1 shadow buffer + jitter) |
| Part 7: Fail-Open | `core/resilience/FailOpenCircuitBreaker.java` | Implemented (3-state circuit breaker + local fallback) |
| Part 8: DST | `dst/` test module | Partial (atomicity tests, not full simulation) |
| Part 9: Decision Framework | N/A — architecture guidance | Documented |

## Gaps

| Blog Mechanism | Status |
|---|---|
| Sliding Window Log algorithm | Not implemented (rejected — O(N) memory) |
| Full DST simulation framework | Not implemented (atomicity tests only) |
| Multi-region token leasing | Not implemented |
| Request ID idempotency | Not implemented |
| Dynamic configuration hot-reload | Not implemented |
