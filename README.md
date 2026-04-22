# Distributed Rate Limiter 🚀

A high-performance, distributed rate limiting service designed for extreme scale. It natively integrates with **Envoy Proxy**, utilizes **Spring Boot gRPC**, and safely synchronizes global state across regions using **Redis**.

## Core Architecture

This repository operates on a three-tier design mirroring tier-1 enterprise architectures:
1. **Edge API Gateway (Envoy)**: Intercepts all client traffic and executes blocking gRPC requests (`rls.proto`) checking against the rate limit service.
2. **Execution Engine (Java 21 Spring Boot)**: Scales horizontally using Kubernetes HPA. Evaluates Envoy descriptors, resolving API keys, tenant IDs, or IP addresses into Rate Limit strategies.
3. **Distributed State (Redis-backed)**: Maintains exact concurrency across multiple instances using isolated Lua scripts to completely prevent **Double Burst Race Conditions**.

### Available Algorithms
The engine currently supports the top industry algorithms for capacity enforcement:
- **Token Bucket**: Classic algorithm for steady refills with fixed burst sizes. Supported natively via `Bucket4j` and Redis Lua atomic arrays.
- **Generic Cell Rate Algorithm (GCRA)**: Theoretical Arrival Time (TAT) computation. The best-in-class algorithm providing perfectly smooth rate execution with fixed **O(1) Memory Footprint**.

---

## Getting Started

### Local Mac / Docker Testing
You can easily simulate a production deployment of Envoy -> Spring Boot gRPC -> Redis via Docker Compose.

```bash
docker-compose up --build
```
Once booted, you will have Envoy listening on port `8080` intercepting downstream hits to the mock target API using strict Java-controlled limits.

### Production Deployment (GCP)
Pre-configured manifest descriptors are within the `/infra/gcp/` path.
```bash
# Deploys standard load balanced services alongside Anti-Affinity deployment mappings
kubectl apply -f infra/gcp/rate-limiter-deployment.yaml

# Secures deployment limits using HPA (Horizontal Pod Autoscaling) and Pod Disruption Budgets
kubectl apply -f infra/gcp/hpa-pdb.yaml
```

---

## 🏗️ Requirements for a Complete Production-Grade Repo
This repository establishes foundational core code. For an absolute "Production-Ready" classification, consider integrating the following aspects:

1. **Continuous Integration (Included)**: The repo currently contains GitHub Actions CI (`.github/workflows/ci.yml`) triggering `Testcontainers` Deterministic Simulation Tests on PR creation to secure concurrency logic.
2. **Helm Charts**: Expanding raw Kubernetes Yaml manifests into a heavily configurable API Gateway Helm Chart.
3. **Observability Stack**:
   - Micrometer Prometheus hooks are configured, but full Grafana dashboards tracking `Envoy_local_rate_limit` against `Ratelimit_cache_hits` are required.
4. **Resiliency Modes (Fail-Open)**: Handling Redis transient outages. If Redis crashes, configuring the Java client to "Fail Opan" rendering the rate limiter entirely non-blocking to protect application availability.

---

## ⚡ Extreme Scale: Lease-Based Prefetching Configurations

At massive scales (e.g., Millions of RPS across microservices), sending individual validation calls to Redis per HTTP request results in major network limits/latency. 

### What is Lease-Based Prefetching?
Instead of fetching one token from Redis, a node "pre-fetches" a bulk lease of tokens for heavy-hitter keys (e.g., a node requests 50 tokens at once from the Redis cluster and decrements them exclusively in local RAM via `Bucket4j`). Redis only communicates cross-network once per 50 requests.

### Configuration Strategy
To implement this in our codebase:
1. **Local Shadow Buffer**: Inside `rate-limiter-core`, `LocalTokenBucketAlgorithm` is placed in front of `RedisGcraAlgorithm` behaving as an L1 Cache.
2. **Proactive Leases**: The Envoy Adapter checks local RAM first. If empty, the gRPC adapter triggers an asynchronous `CompletableFuture` requesting `10x cost` from Redis Token Bucket script at once.
3. **Lease Expiration / Jitter**: When nodes lease tokens for keys, set an exact absolute lease Expiration (e.g., 2000 milliseconds) on the subset tokens, avoiding single-node monopolizations using network timing jitters.
