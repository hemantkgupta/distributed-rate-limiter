# Distributed Rate Limiter �� Research Checkpoint

## Direction

Build a distributed rate limiter for a public API platform handling 250,000 req/sec sustained, 100 million active entities, with <5ms P99 latency and O(1) memory per key.

## Foundation

### 1. Algorithms (Blog Part 3)

Five algorithms implemented, each with distinct tradeoffs:

| Algorithm | Memory/Key | Boundary Burst | Burst Allowance | Use Case |
|---|---|---|---|---|
| Fixed Window | 8 bytes | **Yes** | No | Simple counters (with known flaw) |
| Sliding Window Counter | 16 bytes | **No** | No | Cloudflare-style approximation |
| Token Bucket | 16 bytes | No | **Yes** | AWS/Stripe API rate limiting |
| Leaky Bucket | 16 bytes | No | No | Strict traffic shaping |
| GCRA | **8 bytes** | No | **Yes** | Telecom/HFT (minimal memory) |

### 2. Atomic Consensus (Blog Part 5)

Redis Lua scripts achieve lock-free atomic check-and-consume. The entire operation (read → decide → write) executes in a single `EVALSHA` — no interleaving, no distributed locks, no race conditions.

### 3. Lease-Based Prefetching (Blog Part 6)

L1 shadow buffer eliminates Redis round-trips for high-volume keys. Bulk lease of N tokens → N-1 local decrements → 1 Redis call per N requests. Jittered lease sizes prevent thundering herd across pods.

### 4. Fail-Open Resilience (Blog Part 7)

Three-state circuit breaker: CLOSED (normal) → OPEN (fallback) → HALF_OPEN (probe). Local token bucket provides degraded-but-bounded rate limiting during Redis outages.

## Recommended Defaults

| Parameter | Default | Rationale |
|---|---|---|
| Algorithm | GCRA | O(1) memory, mathematically perfect smoothing |
| Capacity | 100 | Burst allowance per key |
| Rate | 10/sec | Sustained rate |
| Lease size | 50 tokens | Balance between cache efficiency and accuracy |
| Jitter | ±50% | Desynchronize pod refetch cycles |
| Circuit breaker threshold | 3 consecutive failures | Trip after 3 failures |
| Circuit breaker open duration | 5 seconds | Before probing recovery |
| Fail-open timeout | 10ms | Max wait for Redis before fallback |
| Redis key TTL | capacity/refillRate × 2 | Auto-expire stale keys |

## Wiki Sources

| Source | Coverage |
|---|---|
| ChatGPT Deep Research Report | Architecture, algorithms, multi-region, capacity |
| Gemini Deep Research Report | Algorithms, correctness, GCP deployment, 58 references |
| RFC 2697 | Token Bucket parameters |
| ATM Forum Traffic Management | GCRA theory |
| FoundationDB whitepapers | Deterministic simulation testing |
