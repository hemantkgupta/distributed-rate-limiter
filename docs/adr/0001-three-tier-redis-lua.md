# ADR-0001: Three-Tier Architecture with Redis Lua Atomic Operations

**Status**: Accepted  
**Date**: 2026-04-22  

## Context

We need a distributed rate limiter for a public API platform handling 250,000 req/sec with 100 million active entities. The rate limiter must enforce per-user, per-API-key, and per-endpoint limits with <5ms P99 latency and O(1) memory per key.

## Decision

**Three-tier architecture: Envoy edge proxy → stateless Java gRPC execution engine → Redis atomic state layer, with Redis Lua scripts for lock-free consensus.**

## Alternatives Considered

### 1. In-Process Rate Limiting (Middleware)
Embed limits directly in application code (Spring interceptor, Express middleware).
**Rejected**: Per-pod counters are not globally consistent. 100 pods each allowing 10 req/sec = 1000 req/sec actual. Bypassed by the double-burst attack.

### 2. Distributed Locks (Redis SETNX)
Acquire a lock per key before read-modify-write.
**Rejected**: Serializes all traffic behind network hops. At 250K req/sec, lock acquisition pileups cause cascading timeouts. Fatal at scale.

### 3. Database-Backed (PostgreSQL)
Store counters in PostgreSQL with SELECT FOR UPDATE.
**Rejected**: RDBMS write path is 10-50× slower than Redis. Cannot sustain 250K writes/sec on counter updates.

## Why Redis Lua for Atomicity

Redis is single-threaded: Lua scripts execute atomically without locks. The entire check-and-consume operation (read state → decide → write state) happens in a single `EVALSHA` command with zero interleaving. At 250K req/sec, dozens of requests for the same user hit Redis concurrently — Lua prevents the double-burst race condition where multiple requests read the same stale balance.

## Why GCRA as the Default Algorithm

| Algorithm | Memory/key | Boundary Burst | Burst Allowance |
|---|---|---|---|
| Fixed Window | 8 bytes | Yes (fatal flaw) | No |
| Sliding Window Log | O(N) | No | No |
| Sliding Window Counter | 16 bytes | No (approx) | No |
| Token Bucket | 16 bytes | No | Yes |
| **GCRA** | **8 bytes** | **No** | **Yes** |

GCRA tracks one value (Theoretical Arrival Time) vs Token Bucket's two (tokens + timestamp). At 100M keys: GCRA = 800MB, Token Bucket = 1.6GB. Half the RAM for identical behavior.

## Why Envoy as the Edge Proxy

Envoy's `ratelimit` filter implements the gRPC `rls.proto` specification natively. Rate limiting is extracted from application code into the network edge — varying tech stacks (Java, Python, Go) don't need to duplicate limit logic.

## Consequences

**Positive**: Lock-free, O(1) per key, <2ms P99, 250K+ ops/sec per Redis shard.  
**Negative**: Redis is a single point of failure → requires fail-open circuit breaker.
