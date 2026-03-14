# ADR-003: In-Memory Bucket4j for Rate Limiting

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD's security section requires "Rate limiting: 100 requests per minute per API key." No authentication system (API keys, OAuth2, JWT) is defined anywhere else in the PRD, which creates an ambiguity: rate limiting cannot be keyed by API key if there is no auth layer to extract one from.

Options considered:

**API-key-based rate limiting with full auth system:** Would require designing an API key issuance, storage, and validation system not described in the PRD — significant scope expansion beyond what was specified.

**No rate limiting for MVP:** Violates the explicit security requirement in the PRD.

**IP-based rate limiting with Bucket4j in-memory:** The PRD specifies the algorithm (rate limiting) and the limit (100/min) but not the key source. Using the client IP address as the rate limit key satisfies the spirit of the requirement without requiring an authentication system. Bucket4j's token bucket algorithm is industry-standard, the `bucket4j-core` library has no external dependencies, and `ConcurrentHashMap<String, Bucket>` provides thread-safe per-IP state.

**IP-based rate limiting with Redis-backed Bucket4j:** Persistent state across restarts; required for multi-instance deployments. Adds an operational dependency on Redis. Appropriate for production hardening but over-engineered for MVP with a single instance.

## Decision

Use **Bucket4j 8.9 in-memory token bucket** for rate limiting, keyed by client IP address (`request.getRemoteAddr()`), implemented in `RateLimitFilter` (`@Order(2)`).

Each unique IP gets an independent `Bucket` created via `bucketSupplier.get()` (a `@Bean Supplier<Bucket>` in `RateLimitConfig`). The bucket is configured with `Bandwidth.classic(100, Refill.intervally(100, Duration.ofMinutes(1)))` — 100 tokens capacity, fully refilled every 60 seconds. The limit is externalized to `app.rate-limit.requests-per-minute` in `application.yml` so it can be overridden per environment. When the bucket is exhausted, `RateLimitFilter` returns HTTP 429 with a JSON body matching the PRD error response structure, without invoking the controller chain.

The rate limit is noted as IP-based (not API-key-based) in code comments and this ADR, flagging it as a known deviation from the PRD's exact wording that should be revisited when an auth system is introduced.

## Consequences

**Easier:**
- No external service dependency — the API container is self-contained for rate limiting.
- Token bucket algorithm is well-understood; `Bucket4j` is production-grade and battle-tested.
- `app.rate-limit.requests-per-minute` can be set to `10000` in `application-test.yml` to effectively disable rate limiting during integration tests.
- `@Order(2)` after `RequestIdFilter` (`@Order(1)`) ensures the requestId is available in the 429 response body and WARN log statement.

**Harder / Constraints:**
- Rate limit state is lost on application restart — buckets are in-memory only. This is acceptable for MVP single-instance deployment.
- Multi-instance deployments (e.g., Kubernetes horizontal scaling) will have per-instance rate limit state, meaning a client can exceed the intended global limit by hitting different instances. Mitigation path: replace `ConcurrentHashMap<String, Bucket>` with `bucket4j-redis` backed storage.
- Rate limiting is by client IP, not API key. If the API is behind a load balancer or proxy that does not set `X-Forwarded-For`, `request.getRemoteAddr()` returns the proxy IP and all clients share one bucket. This should be addressed when deploying behind a reverse proxy by reading `X-Forwarded-For` or `X-Real-IP` headers.
- The PRD does not define an error code for rate limit responses; `RATE_LIMIT` is used as a non-standard code in the 429 response body.
