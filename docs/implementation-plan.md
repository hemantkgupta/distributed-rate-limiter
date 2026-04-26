# Distributed Rate Limiter — Implementation Plan

## Module Map

| Module | Type | What It Does |
|---|---|---|
| `rate-limiter-core` | Library | Domain types (Decision, LimitDescriptor), all 5 algorithms (FixedWindow, SlidingWindowCounter, TokenBucket, LeakyBucket, GCRA), lease-based prefetcher, fail-open circuit breaker, multi-limit evaluator |
| `rate-limiter-redis` | Library | Redis-backed algorithms via Lua scripts, Redis config |
| `rate-limiter-service` | Spring Boot | gRPC server implementing Envoy `rls.proto`, HTTP admin |
| `rate-limiter-envoy-adapter` | Library | Envoy control plane API protobuf bindings |
| `rate-limiter-dst` | Tests | Distributed systems testing: Testcontainers + Redis atomicity tests |
| `infra/` | Config | Docker Compose, Envoy config, Kubernetes manifests |

## Phase Code Maps

### Phase 1 — Core Algorithms (rate-limiter-core)

- `core/Decision.java` — Result record: allowed, remaining, resetAfterMs, retryAfterMs, headers
- `core/algorithm/LimitAlgorithm.java` — Interface: `checkAndConsume(String key, long cost) → Decision`
- `core/algorithm/FixedWindowAlgorithm.java` — Epoch-aligned counter per window. Demonstrates boundary burst flaw.
- `core/algorithm/SlidingWindowCounterAlgorithm.java` — Weighted previous+current window approximation. Fixes boundary burst.
- `core/algorithm/TokenBucketAlgorithm.java` — Lazy refill: O(1) state [tokens, lastRefill]. Burst-allowing.
- `core/algorithm/LeakyBucketAlgorithm.java` — Lazy drain: O(1) state [level, lastUpdate]. Strict traffic shaping.
- `core/algorithm/GcraAlgorithm.java` — Single-dimension TAT tracking. 8 bytes per key. Mathematically perfect smoothing.
- `core/algorithm/LocalTokenBucketAlgorithm.java` — Bucket4j-based local implementation (pre-existing).

### Phase 2 — Lease-Based Prefetching (rate-limiter-core)

- `core/lease/LeaseBasedPrefetcher.java` — L1 shadow buffer: bulk-fetches tokens from Redis, serves subsequent requests from local AtomicInteger. Jittered lease sizes to desynchronize pod refetch cycles.

### Phase 3 — Fail-Open Resilience (rate-limiter-core)

- `core/resilience/FailOpenCircuitBreaker.java` — Three-state circuit breaker (CLOSED/OPEN/HALF_OPEN). Falls back to local TokenBucket when Redis is down. Fail-open: never blocks legitimate traffic.

### Phase 4 — Multi-Limit Descriptors (rate-limiter-core)

- `core/descriptor/LimitDescriptor.java` — Compound key matching Envoy's descriptor model. Hierarchical entries.
- `core/config/LimitDefinition.java` — Rate limit rule: descriptor pattern, algorithm type, capacity, rate.
- `core/config/MultiLimitEvaluator.java` — Evaluates request against all matching limits. AND semantics: all must pass.

### Phase 5 — Redis Integration (rate-limiter-redis)

- `redis/RedisTokenBucketAlgorithm.java` — Executes `token_bucket.lua` via EVALSHA
- `redis/RedisGcraAlgorithm.java` — Executes `gcra.lua` via EVALSHA
- `redis/RedisConfig.java` — Spring Data Redis config, script loading
- `scripts/token_bucket.lua` — Atomic: HMGET state → refill → check → HMSET + EXPIRE
- `scripts/gcra.lua` — Atomic: GET TAT → allow_at check → SET new TAT + EXPIRE

### Phase 6 — gRPC Service (rate-limiter-service)

- `service/grpc/EnvoyRateLimitGrpcService.java` — Implements `rls.proto`. Extracts descriptors, calls algorithm, builds response with rate limit headers.
- `service/RateLimiterConfig.java` — Configures which algorithm to use
- `service/RateLimitApplication.java` — Spring Boot entry point

## Gaps vs Production

| Production Feature | Status | Notes |
|---|---|---|
| Redis Cluster sharding | Not implemented | Single Redis node locally |
| Multi-region federation | Not implemented | Blog Part 6 describes token leasing |
| Helm charts | Not implemented | Raw K8s manifests provided |
| Grafana dashboards | Not implemented | Prometheus metrics exported |
| Request ID idempotency | Not implemented | Documented in research |
| Hot-reload configuration | Not implemented | Static config |

## Tech Stack

- **Java 21** — Virtual Threads
- **Spring Boot 3.2.3** — gRPC server, HTTP admin, Redis, Actuator
- **Redis 7.2** — State layer with Lua script atomicity
- **Envoy 1.28** — L7 API gateway with `ratelimit` filter
- **gRPC** — Envoy ↔ rate limiter communication (rls.proto)
- **Testcontainers** — Redis integration tests
