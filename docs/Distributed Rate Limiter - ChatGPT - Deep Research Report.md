# Distributed Rate Limiter

A buildable distributed rate limiter for a public API platform should be treated as an admission-control system, not just a “429 generator.” The design has to protect shared capacity, express product entitlements, survive partial failures, expose understandable client behaviour, and remain cheap enough to sit on the hot path of every request. The right learning artifact is therefore a single-region, Redis-backed distributed rate-limit service with explicit multi-tier enforcement, multiple algorithms behind one interface, strong observability, and a deliberate story for failure policy and later evolution to multi-region concessions and lease-based local budgets. citeturn11view1turn32view0turn11view8turn22view0turn30view1

## Executive recommendation

### Flagship design to build first

The first system I would build is a **single-region, centralized rate-limit service** backed by entity["company","Redis","database software company"], with **token bucket as the default enforcement algorithm**, **Lua or Redis Functions for atomic check-and-consume**, and **hierarchical descriptor-based policy evaluation** exposed over **gRPC for gateways** and optionally HTTP for admin/read APIs. The service should support multiple matched limits per request, but make explicit that **single-key decisions are atomic while multi-key, cross-slot decisions are only per-key atomic** on a sharded cluster. That tradeoff is the most important real-world lesson in this problem. Redis scripts execute atomically and reduce TOCTOU races by colocating logic with data; Redis Cluster requires all keys touched by one script to live in the same slot, with hash tags as the standard mechanism for colocating related keys. Redis 7’s Functions model improves operational ergonomics over ad hoc `EVAL`, but Lua scripts remain the clearest first teaching artifact because they surface the distributed constraints directly. citeturn11view2turn11view3turn13view1turn25search2turn33view0

### What to implement first

Implement in this order: an in-memory library of algorithms; a single-node Redis-backed token bucket with atomic consume; hierarchical policy matching; standard 429 semantics with `Retry-After`; shadow mode; explicit fail-open and fail-closed behaviour; Redis Cluster sharding with a documented key strategy; and only then local fallback buckets, token leasing, and cross-region approximation. That sequence gives you a system that is useful in production early, while preserving the deeper distributed lessons for later phases. This mirrors how production systems often start with a request-rate limiter first and only later add concurrency guards and load shedders. At entity["company","Stripe","payments company"], the request-rate limiter is explicitly described as the first and most important limiter to build, while additional classes like concurrent-request limiting and load shedding are layered in afterwards. citeturn26view0turn26view1turn26view5

### What should be treated as contrast or advanced mode

Treat the following as contrast or advanced mode rather than the first artifact: purely local in-process limiters; full gateway/service-mesh integration using Envoy’s external rate-limit protocol; lease-based local budgets; multi-region global limits; and strongly consistent CP-store-backed designs. Envoy’s global rate limiting and quota-based RLQS are excellent advanced design families, but RLQS is explicitly marked work-in-progress and is best understood after the simpler centralized service exists. Strongly consistent stores such as Spanner or etcd are useful contrast because they sharpen the latency-versus-exactness tradeoff, but they are the wrong default hot-path artifact for high-QPS per-request limiting. citeturn11view8turn11view9turn11view10turn11view13turn11view14

### Why this is the right learning artifact

This design is the best learning artifact for the same reason an AP-style distributed KV store is such a good flagship system design: it forces you to grapple with state, partitioning, consistency, timing, control-plane versus data-plane separation, and operator intent, but it is still small enough to implement end to end. A local-only limiter is too easy; a multi-region exact global quota system is too expensive and too policy-specific; a CP design teaches the wrong default operational instincts for a hot-path API platform. The centralized Redis-backed service sits in the sweet spot between realism, tractability, and architectural depth. citeturn22view0turn21search5turn20view0turn19view0

### Decision log

The major decisions I would lock in are these. **Decision:** use token bucket as the default policy model. **Reason:** burst tolerance and weighted-cost support matter more for public APIs than exact “requests in the last rolling minute” semantics. **Decision:** keep enforcement in a dedicated service, not in every app. **Reason:** policies, metrics, and failure mode are operational concerns that should be owned centrally. **Decision:** use Redis Cluster, but document that cross-slot multi-limit evaluation is not globally atomic. **Reason:** this is the real tradeoff principal engineers need to articulate. **Decision:** start single-region exact and make multi-region approximation a later, explicit mode. **Reason:** edge and WAN coordination are expensive enough that they must be opt-in, not accidental. These are engineering recommendations, but they are grounded in the production patterns and constraints reflected across Redis, Envoy, Kong, Cloud Armor, and Cloudflare documentation. citeturn28view0turn30view1turn27view0turn11view8turn11view11

## Problem boundary and requirements

### Product boundary

This rate limiter is for a **public API platform / multi-tenant SaaS** where the same request may need to match **gateway or edge IP limits, tenant plan limits, user or API-key limits, endpoint-specific limits, auth-scope limits, and feature-level quotas**. It must tolerate short bursts, preserve fairness, prevent abuse, provide correct `429 Too Many Requests` semantics, expose `Retry-After`, and optionally emit the emerging IETF `RateLimit` and `RateLimit-Policy` headers. The HTTP standard defines 429 for rate limiting and permits `Retry-After`; the current IETF work on `RateLimit` header fields is still an Internet-Draft as of September 2025 and should be treated as standards-track work in progress rather than a finished RFC. citeturn32view0turn32view1turn32view2

The policy model should support **free/pro/enterprise plan tiers**, **dynamic config updates**, **shadow mode**, **gradual rollout**, and **multiple actions**: hard reject, soft throttle, or delayed shaping for selected internal workflows. Public abuse-sensitive endpoints often want conservative or fail-closed behaviour; internal service-to-service APIs often prefer fail-open with monitoring. This distinction is not defined by RFCs; it is an engineering recommendation, but it aligns with the broader separation between rate limiting and throttling in Redis’s current guidance and with production gateway patterns that distinguish strict limits from throttled or shadowed enforcement. citeturn11view1turn31view0turn22view0

### What is out of scope

The main hot-path limiter should **not** also own every other protection concern. I would keep these out of scope for the first canonical page and implementation: full DDoS mitigation, WAF rule authoring, bot scoring, billing-grade monthly quota accounting, request concurrency limiting for long-running jobs, and general-purpose queuing. Those may integrate with the rate limiter, but they are separate systems. This is exactly how production stacks separate request-rate limiting from other controls like load shedding, concurrency guards, and edge security rules. citeturn26view0turn26view5turn30view1

### Clarifying questions that matter in an interview

The highest-value clarifying questions are these. How exact must enforcement be, and what bounded overshoot is acceptable? What latency budget may the limiter add to the request path? Is this single region or multi-region from day one? Is the action hard block, soft throttle, delay, queue, or best-effort logging? Which dimensions define identity: IP, API key, user, tenant, endpoint, auth scope, or request cost? Are there tiers and overrides? Do retries occur, and must the limiter be idempotent per request ID? Are config changes frequent, audited, and instantly effective? Are we protecting fairness, preventing abuse, or both? The answers move you between local, centralized, lease-based, or globally approximate architectures. citeturn30view1turn27view0turn31view0turn22view0

## Foundation architecture

### Architecture shape

The baseline path is: **Gateway or service interceptor → rate-limit service → Redis Cluster → application service**. In front of that, I would optionally place a very coarse **local per-process token bucket** to absorb bursts and protect the centralized service; Envoy’s own documentation explicitly recommends combining local and global rate limiting so that local token buckets absorb burst load before the global limiter takes the precise decision. If the request passes, the application runs. If it fails, the gateway returns 429 with policy metadata and a retry hint. Envoy’s HTTP filter model is instructive here: one request can produce multiple descriptors, and if any descriptor is over limit, the request is rejected. citeturn11view8turn31view0turn11view9

```text
Client
  -> API Gateway / Envoy / Service Filter
      -> local emergency bucket (optional)
      -> RateLimitService.CheckAndConsume(request, descriptors, cost, requestId)
          -> match policies from local config snapshot
          -> evaluate matched limits in deterministic order
          -> call Redis atomic function/script per impacted key-group
      -> allow => upstream app
      -> deny  => 429 + Retry-After + policy headers
```

### API surface

The service API should expose five core verbs. `check` answers whether a request would pass without consuming. `consume` performs idempotent check-and-consume. `peek` returns current state for debugging or admin views. `refund` reverses a previously consumed amount when that policy model allows it. `batchCheck` evaluates several independent descriptors in one call to reduce network overhead. For gateway integration, the internal gRPC API can look similar to Envoy RLS and include `hits_addend`/cost semantics; Envoy’s proto explicitly supports multiple descriptors per request and a per-request addend rather than a fixed cost of one. That makes weighted rate limiting a first-class feature instead of a later hack. citeturn11view9turn31view0

A canonical internal request payload should include these fields: `requestId`, `tenantId`, `principalId`, `remoteIp`, `method`, `routeTemplate`, `authScope`, `cost`, `mode`, `enforcementMode`, and `policyContext`. The response should include `allowed`, `status`, `matchedPolicies`, `consumedPolicies`, `remaining`, `retryAfterMs`, `resetAfterMs`, `shadowWouldBlock`, `configVersion`, and `headers`. Returning structured matched-policy information is not required by standards, but it is operationally invaluable for debugging, shadow mode, and replay testing. Envoy and the reference ratelimit service both surface rich descriptor-oriented semantics that are worth mirroring. citeturn22view0turn31view0

### Data model

The control plane needs a versioned policy model. The core record should be a `LimitDefinition` with fields like: `policyId`, `scopeType`, `scopeSelector`, `algorithm`, `windowOrRate`, `capacity`, `burst`, `weightExpression`, `priority`, `enforcementMode`, `failureMode`, `shadow`, `headersPolicy`, `ttlPolicy`, and `metadata`. A separate `PlanBinding` should map tenants or products to policy sets. The reference ratelimit service described by entity["company","Lyft","ride hailing company"] and the current upstream `envoyproxy/ratelimit` both use a **domain plus hierarchical descriptors** model with nested keys and shadow mode. That structure is a very strong foundation for your own rate limiter because it directly expresses tenant, user, endpoint, and feature-level hierarchy. citeturn22view0turn22view1turn11view9

For hot-path state, keep one Redis key per active limit instance. Use short-lived TTLs so inactive subjects disappear automatically. Redis’s `EXPIRE` and `TTL` semantics are the right primitive here: keys become volatile and auto-delete when idle. Suggested key shapes are:
`rl:{tenant}:tb:{endpoint}:{user}` for token bucket,
`rl:{subject}:fw:{window}` for fixed window,
`rl:{subject}:swc:{currentWindow}` and `rl:{subject}:swc:{prevWindow}` for sliding-window counter,
`rl:{subject}:log` for sliding log,
and `rl:{subject}:gcra` for GCRA state. The TTL policy should be algorithm-specific: for fixed and sliding counters, around one to two windows; for token bucket, roughly full-refill time plus a buffer; for GCRA, enough to cover burst tolerance plus refill horizon. citeturn10search0turn10search1turn13view1turn13view2turn13view3

### Redis atomicity and sharding

The first correctness rule is simple: **never do read-then-write across separate Redis round trips for a limiter**. Redis’s own rate-limiting tutorial calls out the TOCTOU problem directly and recommends server-side Lua for the read-decide-write sequence. The second rule is equally important in Cluster mode: **any one script or function may only touch keys in the same hash slot**. Hash tags are how you force related keys to colocate. This matters immediately for sliding-window counter, which needs both current and previous windows together; it also matters for any attempt to evaluate multiple related counters atomically. citeturn11view0turn11view2turn11view3turn13view0

This leads to the central architecture tradeoff. A **single-key limit** is easy to make atomic on Redis Cluster. A **set of limits across several dimensions** is not globally atomic if those dimensions naturally hash to different slots. My recommendation is to be explicit: define the system guarantee as **per-key atomic, multi-key best-effort with deterministic order and compensating refunds where needed**. If a use case truly requires all-or-nothing exactness across tenant, user, endpoint, and IP simultaneously, you either co-locate them artificially—which creates hot spots and distorts aggregation semantics—or move that class of policy to a CP data store, which is usually too slow and too expensive for the common path. citeturn11view3turn21search5turn11view13turn11view14

### Response headers and client semantics

For HTTP, the service should return `429 Too Many Requests` when blocked, and should include `Retry-After` whenever a meaningful retry bound is computable. That is directly aligned with RFC 6585 and HTTP Semantics. I would also emit the work-in-progress `RateLimit` and `RateLimit-Policy` response headers for modern clients, while optionally preserving legacy `X-RateLimit-*` headers for compatibility. Because the IETF RateLimit fields are still draft material, document them as an interoperability feature, not a stable Internet Standard. citeturn32view0turn32view1turn32view2

### Config distribution, hot reload, and multi-limit evaluation

The control plane should publish **immutable versioned snapshots**, not mutate policy fragments in place. The rate-limit service should keep a local snapshot cache, atomically swap to new versions, and expose the active `configVersion` in metrics and debug responses. The production reference ratelimit service supports both file/runtime-based config and xDS-backed configuration and also has explicit `shadow_mode`, which is powerful evidence that dynamic config and dry-run are first-class operational needs, not embellishments. citeturn22view0

When one request matches multiple limits, evaluate them in a deterministic order. I recommend this order: emergency deny or kill switches first; coarse abuse protectors like IP or auth-source limits next; tenant hard quotas next; then user and endpoint limits; then optional feature or soft limits. If any hard policy rejects, return immediately but still optionally continue evaluating the remaining policies in **shadow** to preserve observability. This matches Envoy’s “multiple descriptors, deny if any over limit” model while still letting you express precedence and shadowed rollout. citeturn11view9turn31view0turn22view0

## Algorithm deep dive

### Why token bucket is the practical default

Token bucket is the best default for public APIs because it gives you exactly what product teams usually want: a sustainable rate, bounded bursts, and a natural way to charge different requests different amounts. Redis’s current tutorial describes token bucket as the right choice when brief spikes are expected and acceptable; Envoy’s local filter is token-bucket-based; and Stripe’s production write-up explicitly says they use token bucket for request rate limiting. That is unusually strong cross-source convergence for a systems-design default. citeturn13view2turn17search0turn26view4

### Fixed window counter

```text
window = floor(now / W)
key = base + ":" + window
count = INCR(key)
if count == 1: EXPIRE(key, W)
allow = count <= limit
retry_after = TTL(key) if !allow
```

**Data model:** one string counter per key per discrete window. **Request path:** increment the current window, set expiry only on first hit, reject if count exceeds limit. **Memory:** `O(active_keys)`. **Latency:** one atomic round trip. **Accuracy:** poor at boundaries. **Burst behaviour:** worst-case ~2× limit across a boundary. **Distributed difficulty:** low for single key; easy to shard. **Failure behaviour:** without Lua or Functions, a crash between `INCR` and `EXPIRE` can strand a key forever. **Best use cases:** simple login throttles, coarse route protection, and very cheap edge rules. **Interview framing:** the simplest baseline, valuable mostly because you can contrast it against boundary bursts and atomicity concerns. Redis’s own tutorial explicitly calls it the simplest algorithm and calls out the boundary-burst problem. citeturn13view1turn12view2

### Sliding log

```text
cutoff = now - W
ZREMRANGEBYSCORE(key, -inf, cutoff)
count = ZCARD(key)
if count < limit:
    ZADD(key, now, unique_member)
    EXPIRE(key, W)
    allow = true
else:
    allow = false
    retry_after = oldest_score + W - now
```

**Data model:** a sorted set of exact request timestamps per subject. **Request path:** prune old entries, count survivors, insert current request if allowed. **Memory:** `O(requests_in_window)` and therefore dangerous at high cardinality. **Latency:** one script, but more internal operations than counter algorithms. **Accuracy:** exact rolling window. **Burst behaviour:** no boundary burst. **Distributed difficulty:** moderate but still single-key. **Failure behaviour:** incorrect uniqueness or failed pruning can corrupt decisions. **Best use cases:** low-QPS high-value endpoints such as OTP sends, password resets, or admin mutations where exactness matters more than memory. **Interview framing:** the accuracy-first answer that you intentionally reject for most high-scale API traffic because memory grows linearly with request volume. Redis’s tutorial makes exactly that tradeoff explicit. citeturn12view2

### Sliding window counter

```text
curr = key(base, floor(now / W))
prev = key(base, floor(now / W) - 1)
prev_count = GET(prev) or 0
curr_count = GET(curr) or 0
elapsed = (now % W) / W
estimate = prev_count * (1 - elapsed) + curr_count
if estimate + cost <= limit:
    INCRBY(curr, cost)
    EXPIRE(curr, 2 * W)
    allow = true
else:
    allow = false
```

**Data model:** two counters, current and previous window, usually with hash tags so both keys land in the same Redis slot. **Request path:** read both counters, compute weighted estimate, increment current if allowed. **Memory:** `O(2 * active_keys)`, effectively constant per subject. **Latency:** one script, still very cheap. **Accuracy:** approximate but usually good enough. **Burst behaviour:** much smoother than fixed window, but not exact. **Distributed difficulty:** higher than fixed window because two keys must share a slot. **Failure behaviour:** incorrect hash-tagging causes cross-slot failures; approximation can admit or reject a small amount of extra traffic around edges. **Best use cases:** large gateways that want near-exact rolling count semantics without a log. **Interview framing:** the best upgrade from fixed-window if the policy model is “X requests in any rolling interval” rather than “average rate plus burst budget.” Redis’s own current tutorial presents it as the best balance for many applications. citeturn13view0turn13view2

### Token bucket

```text
state = HGETALL(key) or {tokens=capacity, last_refill=now}
tokens = min(capacity, state.tokens + (now - state.last_refill) * refill_rate)
if tokens >= cost:
    tokens = tokens - cost
    allow = true
else:
    allow = false
retry_after = ceil((cost - tokens) / refill_rate)
HSET(key, tokens, last_refill=now)
EXPIRE(key, refill_horizon)
```

**Data model:** a hash with `tokens` and `last_refill`, or the equivalent fixed-point fields. **Request path:** lazily refill using elapsed time, then consume cost if enough tokens remain. **Memory:** `O(active_keys)`. **Latency:** one script; excellent hot-path performance. **Accuracy:** very good if all arithmetic is done atomically and using a stable time source. **Burst behaviour:** allows bursts up to capacity. **Distributed difficulty:** moderate but easy for one key. **Failure behaviour:** clock skew and floating-point drift are the main pitfalls; fixed-point integer arithmetic is safer than doubles. **Best use cases:** almost all public API request-per-second limiting, especially when users naturally burst or when requests have different costs. **Interview framing:** the practical default because it expresses both sustained rate and burst budget clearly. Redis’s tutorial, Envoy’s local filter docs, and Stripe’s production write-up all support this positioning. citeturn13view2turn17search0turn26view4

### Leaky bucket

```text
# policing form
state = {level, last_leak}
level = max(0, state.level - (now - state.last_leak) * leak_rate)
if level + cost <= capacity:
    level += cost
    allow = true
else:
    allow = false
save(level, now)

# shaping form
next_free = GET(key) or now
scheduled = max(now, next_free)
if scheduled - now <= queue_budget:
    next_free = scheduled + service_interval * cost
    allow_and_delay_until(next_free)
else:
    reject
```

**Data model:** either a meter (`level`, `last_leak`) or a queue / `next_free` schedule. **Request path:** policing rejects immediately on overflow, while shaping delays accepted work to smooth output. **Memory:** `O(1)` for policing, `O(queue_depth)` for explicit shaping. **Latency:** one script for policing; shaping also needs a scheduler or delayed dispatch. **Accuracy:** excellent for enforcing a smooth drain rate. **Burst behaviour:** essentially no burst in the output path. **Distributed difficulty:** moderate; shaping is operationally harder than policing. **Failure behaviour:** shaping can fail badly if the drain scheduler stalls. **Best use cases:** network-shaped egress, worker queues, or protecting dependencies that cannot tolerate spikes. Official NGINX documentation explicitly says its request limiter uses the leaky-bucket method, and Redis’s algorithm guide distinguishes policing from shaping the same way. **Interview framing:** not the best default for user-facing APIs, but the better answer when the output must be smoothed rather than merely capped. citeturn11view5turn13view3turn15view0

### GCRA and virtual scheduling

```text
# EI = emission interval, burst = tau
tat = GET(key) or now
allow_at = tat - burst
if now >= allow_at and cost <= max_burst_cost:
    tat = max(now, tat) + cost * EI
    SET(key, tat, EX=ttl)
    allow = true
else:
    allow = false
retry_after = allow_at - now
```

**Data model:** one scalar state, usually theoretical arrival time (`TAT`). **Request path:** compare current time to the next admissible virtual time, then advance schedule if allowed. **Memory:** `O(1)`. **Latency:** one atomic round trip. **Accuracy:** very strong rolling-window-like semantics with no background drip process. **Burst behaviour:** elegant and precise via burst tolerance. **Distributed difficulty:** moderate and similar to token bucket. **Failure behaviour:** sensitive to time source and idempotency, but not to background refill jobs. GCRA is historically rooted in ATM traffic policing; RFC 2381 describes conformance in terms of GCRA, and Brandur’s Redis material and `redis-cell` show how naturally it maps to a Redis implementation. **Best use cases:** extremely clean API limiters where exact retry-after and rolling-window semantics matter. **Interview framing:** a more mathematically elegant alternative to token bucket, especially strong if you want one-scalar state and exact virtual-scheduling semantics. citeturn16view0turn15view2turn15view3

### Weighted token buckets and hierarchical limits

Weighted rate limiting should be first-class. Envoy’s protocol already includes a `hits_addend`; Cloudflare’s advanced docs show why cost-based limiting matters for APIs and GraphQL-style workloads where requests vary widely in server cost; and that same principle maps directly to token bucket or GCRA by charging `read = 1`, `search = 5`, `export = 50`, or whatever cost model you choose. The key rule is that `capacity` must be at least the largest single permitted request cost, otherwise some operations become permanently impossible. citeturn11view9turn19view1turn27view0

```text
cost = classify(request)            # e.g. read=1, search=5, export=50
matched = [ip_limit, tenant_limit, user_limit, endpoint_limit]
for limit in matched in priority_order:
    decision = consume(limit.key, cost_or_1(limit, cost))
    if decision == DENY:
        optionally_refund(previously_consumed)
        return DENY
return ALLOW
```

Hierarchical limits are what turn a limiter from an interview sketch into a platform service. Structure them as `tenant + user + endpoint + IP + auth scope`, but do not pretend they are globally atomic across arbitrary shards. Use them to express safety from coarse to fine: first protect the platform, then the tenant, then the actor, then the expensive feature. The descriptor tree model used by Envoy RLS and the reference ratelimit service is a strong conceptual fit for this hierarchy. citeturn11view9turn22view0turn22view1

## Distributed correctness and failure handling

### Correctness invariants

The hard invariants are these. **No check-update races.** Redis’s own guidance is explicit: read, decide, and write must happen in one server-side atomic unit. **No hidden cleanup jobs for core correctness.** If correctness depends on an external drip process, you have created a second availability dependency. **Time must be deliberate.** Older Redis scripting rules treated `TIME` as non-deterministic for write scripts unless effects replication was used; in modern Redis, effects replication is the default, and Redis Functions explicitly demonstrate calling `TIME` inside server-side logic. That means you can choose either shard-local Redis time for per-key correctness or an application-supplied monotonic clock for deterministic testing. Both are valid, but the choice must be explicit. citeturn11view0turn11view2turn24view0turn33view0

My recommendation is this. For **single-shard correctness**, prefer **Redis server time** inside the function or script because it removes skew across app nodes. For **deterministic simulation**, support an alternate mode where the caller passes `now`. The service should reject mixed modes in production. This split keeps production semantics simple while making your simulator and unit tests precise. Redis’s own examples use app-provided time for tutorials, while Redis Functions show server-side `TIME`; both patterns are useful, but for a principal-level design you want both, each in its right place. citeturn13view3turn33view0

### Idempotency, retries, and refunds

A real distributed limiter needs **request ID idempotency**. Without it, a gateway retry after a timeout can double-charge the same request. The usual pattern is an idempotency key with TTL slightly longer than the client retry horizon, storing the prior decision and the set of policies consumed. Refunds are then allowed only when that consume record exists and has not already been refunded. This is an engineering recommendation rather than a sourced standard, but it follows directly from the retry and atomicity problems that Redis and Envoy’s descriptor model surface. citeturn11view0turn11view9

Refund policy must be narrow. Do **not** automatically refund every downstream 5xx. Rate limiting is usually about **attempted capacity consumption**, not only successful business outcomes. A request that reached your app already consumed CPU, connections, and queue slots. Refund automatically only when the request failed before entering application execution, or when you are implementing a two-phase reservation model for jobs. Otherwise, retries can become a capacity amplification vector. This is one of the most common hidden design mistakes.  

### Multi-limit ordering and cross-slot tradeoffs

On a standalone Redis node you can evaluate several keys in one atomic script; on Redis Cluster you can only do that when the keys share a slot. That is where many designs become hand-wavy. The honest answer is that **exact all-or-nothing enforcement across tenant, user, endpoint, and IP is not the default property of a sharded Redis design**. The right answer is either: keep each decision per-key atomic and accept small inconsistency windows; co-locate a subset of related counters that must be exact together; or move a tiny class of globally exact policies to a CP system. Kong’s own rate-limiting docs make the same distinction between high-accuracy shared counters and best-effort backend protection and even quantify cluster-wide overage under asynchronous sync. citeturn28view0turn11view3turn21search5

### Hot keys, config rollout, and shadow mode

Hot keys are inevitable: one enterprise tenant, one login endpoint, one abusive IP. The first tool is algorithm choice: sliding logs are worst here, token bucket and GCRA are much better. The second is decomposition: separate coarse tenant quota from expensive endpoint quota so not every request hits the same ultra-hot key. The third is local absorption: a small local token bucket or token lease can flatten the burst that would otherwise hammer Redis. Envoy explicitly recommends local plus global rate limiting for this reason, and RLQS exists precisely because high-QPS global fairness across many gateways eventually benefits from local quota assignments rather than per-request central checks. citeturn11view8turn11view10turn17search0

Config rollout should always have **shadow mode** and **fractional enforcement**. The reference ratelimit service has explicit shadow mode, and Envoy supports runtime-controlled enabling and enforcement percentages. That means you can deploy new policies in evaluate-only mode, log what they would have blocked, then canary to a percentage before full enforcement. Shadow mode is not a nicety; it is one of the core correctness tools for policy systems. citeturn22view0turn17search1turn31view0

### Failure modes and mitigations

**Redis node failure or failover.** Symptom: latency spike, MOVED/ASK churn, temporary decision failures. Impact: transient allow/deny errors and possible write loss because Redis Cluster uses asynchronous replication and documents a write-loss window during partitions or failovers. Mitigation: replica per shard, Redis-aware clients, small timeouts, circuit breakers, emergency local buckets, and product-specific fail-open or fail-closed policy. Metrics: failover count, replica lag, cluster state, fallback-decision rate, decision timeout rate. citeturn11view12turn21search5turn21search0

**Redis latency spike.** Symptom: p99 explosions in the limiter path. Impact: gateway queues and user-visible latency. Mitigation: keep scripts `O(1)` and short because scripts and functions block server activities while running; preclassify descriptors before Redis; layer in local token buckets so bursts do not all reach the global path. Metrics: Redis command latency, script runtime, gateway queue depth, 429 rate, fallback usage. citeturn11view2turn13view1turn11view8

**Rate-limit service outage.** Symptom: gateway cannot fetch decisions. Impact depends on policy: either accidental allow-all or widespread denial. Mitigation: per-route failure policy, cached config, local emergency rules, and circuit breaking so an unhealthy limiter is quickly isolated. Metrics: gRPC error rate, timeout rate, failure-mode-allowed counters, endpoint-specific decision availability. Envoy’s filter exposes both over-limit and failure-mode-allowed statistics, which is a good pattern to copy. citeturn31view0

**Bad config rollout.** Symptom: sudden traffic drops or total allowance inflation. Impact: either user pain or abuse window. Mitigation: schema validation, replay against historical traffic, shadow mode, per-tenant canaries, immutable versioned configs, and a one-click rollback. Audit log every config change. Production reference services explicitly support shadow mode and dynamic config loading because this is a primary operational risk. citeturn22view0

**Key explosion.** Symptom: Redis memory growth, high churn, eviction pressure. Impact: the limiter becomes a memory-management problem. Mitigation: normalize route templates, cap permitted dimensions, TTL every hot-path key, and avoid raw path or header cardinality unless explicitly configured. This matters even more for advanced edge products that allow arbitrary header/cookie/body selectors, as Cloudflare’s advanced rate limiting shows. citeturn19view1turn27view0

**Local fallback abuse.** Symptom: attackers exploit fail-open behaviour during control-plane incidents. Impact: safety limits vanish exactly when you need them. Mitigation: keep local emergency budgets small and time-bounded, make abuse-sensitive endpoints fail-closed or conservative, and alert on fallback usage. This is a pure engineering recommendation, but it follows from the documented approximation and region-local enforcement behaviour of Cloudflare and Cloud Armor: approximate systems are acceptable for abuse mitigation only when their approximation is understood and bounded. citeturn19view2turn30view1

## Multi-region, scale, and operations

### Multi-region designs for global limits

The simplest multi-region story is **region-local enforcement only**. Every region maintains its own exact local counters. This gives excellent latency and high availability, but no true global cap. It is often acceptable for IP-based abuse control because traffic is usually region- or PoP-sticky anyway. Both Cloudflare and Cloud Armor explicitly document per-location or per-region scoped counters and warn that their enforcement is approximate rather than globally exact. citeturn27view0turn30view1turn19view0

The next step is **static per-region budget allocation**. If the global quota is 10,000 rps, allocate 4,000 to the largest region, 3,000 to the next, and so on. This is easy to reason about and fail-safe, but wastes capacity under imbalance. Good for predictable geography, weak for flash events.  

A better advanced design is **dynamic token leasing**. Regions ask a global allocator for chunks of tokens, then satisfy requests locally until the lease expires or is exhausted. This is conceptually close to Envoy’s quota-based RLQS, where the data plane periodically reports usage and receives quota assignments back. The upside is drastically lower WAN coordination on the request path; the downside is bounded overshoot equal to outstanding leases plus any local emergency buffers. That is the correct way to explain lease-based systems in an interview: “We traded exact global counts for bounded overshoot and preserved local latency.” citeturn11view10turn11view8

If you want **eventually consistent global counters**, CRDT-style counters are the right conceptual family. Redis Active-Active says directly that its geo-distributed mode is based on CRDT technology and offers strong eventual consistency. The CRDT counter literature also makes the tradeoff explicit: classic strongly consistent counters are unsuitable for large-scale geo-replicated scenarios, but naive CRDT counters can suffer identity explosion. This is why I would treat CRDT global limits as **soft quotas, analytics, or traffic shaping hints**, not as strict financial or abuse-blocking guarantees. citeturn19view3turn20view0

The design to avoid for the common path is **central global exactness** using a single globally coordinated Redis or CP database. Cloudflare’s engineering write-up says plainly that a single central point was unrealistic because latency was too high and making that central service highly available was a challenge. etcd documents that cross-region deployments pay pronounced consensus latency because a majority must answer each consensus request, and Spanner documents external consistency and strong guarantees that are excellent for correctness but inherently more coordination-heavy than regional counters. The right guideline is therefore: **use strong consistency only for low-QPS policy classes where exact global sequencing is worth the latency**. citeturn19view0turn11view13turn11view14

### Capacity estimates and scaling assumptions

The following are **engineering sizing assumptions**, not vendor guarantees. For a centralized Redis-backed token-bucket service, plan around **20k–50k hot-path ops/sec per shard** as a conservative sustained starting target, not as a hard maximum. At **100k QPS**, a modest Redis cluster with a handful of shards and well-behaved scripts is realistic. At **1M QPS**, the architecture still works, but local token buckets and aggressive sharding stop being optional. At **10M QPS**, per-request central checks become expensive enough that you should expect to need gateway-local enforcement, token leasing, or quota assignment. This conclusion is consistent with Envoy’s split between per-request global checks and quota-based fair sharing for high-QPS deployments. citeturn11view8turn11view10

For memory, a token bucket or GCRA state is operationally tiny compared with a sliding log. As a planning rule, budget **roughly a few hundred bytes per active key** once Redis object overhead and allocator fragmentation are included. That means **10 million active token-bucket keys** is plausibly a **single-digit to low-double-digit GB** memory problem before replicas and headroom, while a sliding log at even a few dozen timestamps per key becomes completely different in scale. Redis’s own algorithm guidance makes that asymmetry clear: counters and buckets are low-memory; logs grow linearly with request volume. citeturn12view2turn13view2turn15view2

For latency budgeting, keep the centralized limiter’s added p99 in the low single-digit milliseconds in one region. That usually means same-zone gateway→RLS→Redis placement, tiny scripts, no blocking config lookups, and a local gate that absorbs short bursts. Redis scripts and functions block the server while running, so algorithmic complexity matters directly to latency. citeturn11view2turn33view0

### Operational design

The metric set should be opinionated. Track: total decisions, allows, denies, shadow-denies, fallback-allows, fallback-denies, decision latency, Redis latency, script runtime, matched-policy cardinality, hot-key top-N, config version skew, idempotency dedupe hits, refunds, and lease exhaustion if you add leasing. Envoy’s filter and the reference RLS both expose useful statistical categories such as `ok`, `error`, `over_limit`, and shadow overrides; copy that shape. citeturn31view0turn22view0

Logs should not be verbose by default, but sampled structured decision logs are invaluable. Emit hashed descriptor identifiers, policy IDs, decision path, failure mode, remaining quota, retry-after, config version, and correlation ID. Traces should add a distinct rate-limit span so you can see whether the user-visible p99 is gateway, limiter, Redis, or downstream.  

Admin tooling should include tenant-specific overrides, emergency bans, “why was this blocked?” introspection, and a config audit trail. Safe deploys require shadow mode, canary policies, and replay against historical access logs. This is exactly the style of deployment discipline Stripe recommends for limiters—dark launch, tune, then enforce—and the reference RLS shadow-mode support exists for the same reason. citeturn26view5turn22view0

## Implementation, deployment, and testing roadmap

### Java companion project

Use repository name **`distributed-rate-limiter`** and target **Java 21 LTS**. I would use **Gradle**, **Lettuce** for Redis, **gRPC + protobuf** for the hot-path API, **Spring Boot** only for the admin/read APIs if you want developer convenience, **Micrometer + OpenTelemetry** for telemetry, and **Testcontainers** for integration tests. Lettuce has built-in support for Redis Lua scripting and for Redis Functions via `FCALL`, which gives you a clean upgrade path from scripts to functions later. citeturn34view0turn33view0

A clean module split is:
- `rate-limiter-core` — domain types, decisions, clocks, idempotency interfaces
- `rate-limiter-algorithms` — fixed window, sliding log, sliding counter, token bucket, leaky bucket, GCRA
- `rate-limiter-config` — policy model, validation, snapshots, audit
- `rate-limiter-redis` — Lua/Function libraries, key mappers, Lettuce adapters
- `rate-limiter-service` — gRPC server, HTTP admin API, policy engine
- `rate-limiter-envoy-adapter` — Envoy RLS-compatible adapter
- `rate-limiter-simulator` — deterministic event scheduler and fake time
- `rate-limiter-loadtest` — Gatling or k6 harness plus microbenchmarks
- `infra` — Docker Compose, Kubernetes manifests, Helm or Kustomize overlays

The core interfaces should look like this conceptually:

```java
interface LimitAlgorithm {
    Decision checkAndConsume(LimitPolicy policy, SubjectKey key, long cost, TimeSource now, String requestId);
    Decision peek(LimitPolicy policy, SubjectKey key, TimeSource now);
    RefundResult refund(LimitPolicy policy, SubjectKey key, long cost, String requestId);
}

interface PolicyMatcher {
    MatchedPolicies match(RequestContext ctx, ConfigSnapshot snapshot);
}

interface DecisionEngine {
    RateLimitResult evaluate(RequestContext ctx, EnforcementMode mode);
}
```

Start with an in-memory state store and deterministic clock so every algorithm can be tested without Redis. Then add `RedisTokenBucketAlgorithm`, `RedisGcraAlgorithm`, and `RedisSlidingWindowCounterAlgorithm` behind the same interface. Keep arithmetic fixed-point. Keep script/function return values compact and typed.  

### Phased implementation plan

**Phase one:** in-memory algorithms and property-based tests. Build exact behavioural tests for burst tolerance, retry-after, window transitions, and weighted costs. Distinguish policing versus shaping for leaky bucket.  

**Phase two:** Redis single-node atomic limiter. Start with token bucket in Lua or Redis Functions, then add GCRA. Prove that retries with the same `requestId` are idempotent.  

**Phase three:** service API and config model. Add gRPC hot path, optional HTTP admin APIs, config snapshots, shadow mode, and 429 headers.  

**Phase four:** Redis Cluster and multi-limit evaluation. Add hash-tagged keys, per-slot evaluation groups, deterministic order, compensating refund hooks, and explicit documentation of atomicity boundaries.  

**Phase five:** local fallback, circuit breaker, and token leasing. Add process-local emergency buckets and later a regional lease manager.  

**Phase six:** multi-region simulation. Add static budget splitting, leasing, and eventually consistent counters in the simulator, then compare overshoot and availability tradeoffs.  

**Phase seven:** GKE deployment, Envoy adapter, observability, and dashboarding.  

**Optional phase eight:** move from `EVALSHA` to Redis Functions for the mainline Redis 7+ deployment path while retaining scripts for portability. Redis Functions are now the official “Redis 7 and beyond” direction, but scripts remain a good didactic artifact and backward-compatible baseline. citeturn33view0turn25search2turn34view0

### GCP and Kubernetes deployment shape

On entity["company","Google Cloud","cloud platform"], the realistic deployment is a **regional GKE cluster** for the rate-limit service, **Envoy or an API gateway** at ingress, and **Memorystore for Redis Cluster** if you want managed Redis rather than self-managed OSS Redis. Memorystore for Redis Cluster is fully managed, shards data across primaries, supports replicas for high availability, and requires at least one replica per shard if you want automatic failover. The tradeoff is command-set and topology flexibility versus operational simplicity. citeturn11view11turn11view12

At the edge, use **Cloud Armor** for coarse IP-based or header-based abuse throttling and temporary bans, not for fine-grained application entitlement logic. Cloud Armor documents approximate regional enforcement and explicitly recommends rate limiting for abuse mitigation and availability, not strict quota or licensing. That makes it a strong first gate and a weak source of truth for plan entitlements. citeturn30view1

For the service deployment itself, use an **HPA** based on CPU plus an external metric like decision QPS or p99 latency, because GKE supports autoscaling on resource, custom, and external metrics. Add a **PodDisruptionBudget** so voluntary disruptions do not take too many limiter pods down at once; Kubernetes documents PDBs precisely for limiting concurrent disruptions to highly available applications. In practice, I would start with at least three replicas across zones, `minAvailable: 2`, and anti-affinity. citeturn29view0turn29view1

### Deterministic simulation and testing

Your simulator should model **time, network delay, retries, and partial failures explicitly**. Run event-driven scenarios for:
- concurrent requests against one key
- duplicate requests with same `requestId`
- Redis latency inflation
- Redis outage and failover
- regional partition
- config rollout race
- token leasing exhaustion and overshoot
- shard clock skew
- hot-key tenant traffic
- fail-open versus fail-closed difference
- refund correctness after downstream failure

The important invariants are not only “did the limiter deny correctly?” but also “what was the overshoot bound?”, “was the same request charged twice?”, and “did fallback policy match endpoint sensitivity?” This is the right place to compare algorithms and architectures, not just unit tests.  

### Knowledge walkthrough

For the **foundation** layer, teach the reader to anchor on product boundary, policy dimensions, API surface, descriptor matching, Redis atomicity, and the main request path. The most important foundation question is not “which algorithm?” but “what is the identity and what behaviour does the product want when the limit is reached?”  

For the **going deeper** layer, focus on hidden traps: GET-then-SET races, Redis Cluster cross-slot limits, clock source, retries and refunds, local fallback abuse, config rollout consistency, and hot-key collapse. Most engineers fail this problem not on algorithms but on these operational details.  

For the **at scale** layer, focus on deployment shape, metrics, approximate global enforcement, lease-based local budgets, and why exact global hot-path counting is rarely worth WAN coordination. Cloudflare, Cloud Armor, Envoy RLQS, Kong strategy docs, and the CRDT counter literature all point in the same direction: exactness is expensive, approximation is acceptable only when bounded and deliberate. citeturn19view0turn30view1turn11view10turn28view0turn20view0

### Common mistakes to call out explicitly

The mistakes worth naming on the wiki page are these. Treating a local per-node limiter as a global limit. Using fixed window without explaining boundary bursts. Doing check-then-update with separate Redis commands. Assuming Redis Cluster can atomically evaluate arbitrary multi-dimensional limits. Using raw paths or arbitrary headers and creating unbounded key cardinality. Forgetting idempotency for retries. Emitting 429 without `Retry-After`. Rolling out enforcement without shadow mode. Choosing a CP store for every request because “correctness,” without pricing the latency cost. These are the mistakes that separate a generic interview answer from a principal-level system design.  

## Open questions and limitations

The main unresolved choice is **how much exactness the product really needs**, especially for global and multi-dimensional policies. If you need globally exact hard quotas across regions, the design changes materially and becomes more expensive. The second open question is whether **concurrency limiting** should live in the same service or a sibling admission-control service; Stripe’s production write-up argues for keeping it conceptually separate. The third is whether the implementation should target **Redis Functions first** or use **Lua scripts first** for wider portability and simpler teaching. My recommendation is scripts first, functions second. citeturn26view1turn33view0

The bottom line is simple. For a canonical wiki page and a real companion implementation, build **a centralized single-region rate-limit service, backed by Redis Cluster, with token bucket as the default algorithm, GCRA and sliding-window counter as contrast algorithms, hierarchical descriptor matching, explicit failure policy, and clear multi-region approximation modes**. That is the design family that best balances educational value, production realism, and implementability.