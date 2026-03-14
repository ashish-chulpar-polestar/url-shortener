# ADR-002: In-Process Rate Limiting with Bucket4j

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD mandates rate limiting at 100 requests per minute per API key. Options considered were: (1) API Gateway-level rate limiting (e.g., AWS API Gateway, Kong), (2) in-process token bucket with Bucket4j backed by an in-memory `ConcurrentHashMap`, and (3) distributed rate limiting with Bucket4j backed by Redis.

For v1 the service is expected to run as a single instance. An API Gateway introduces additional infrastructure that is out of scope for the initial release. Redis adds an operational dependency (connection pool, failover, serialisation) that is not justified for a single-node deployment. Bucket4j's in-process `LocalBucketBuilder` provides the token-bucket algorithm with zero external dependencies.

## Decision

Use **Bucket4j 8.7.0** (`bucket4j-core`) with an in-memory `ConcurrentHashMap<String, Bucket>` keyed by the `X-API-Key` request header. The `RateLimitFilter` (`@Order(2)`) creates one `Bucket` per unique key using `Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1))`. Requests exceeding the limit receive HTTP 429 with a `Retry-After: 60` header. Requests missing the `X-API-Key` header receive HTTP 400 with `ERR_102`.

## Consequences

- **Easier:** No external cache infrastructure required; straightforward to configure via `app.rate-limit.requests-per-minute`; bucket state is visible in-process for testing.
- **Harder:** Rate limit state is lost on application restart; buckets are not shared across multiple service instances. If horizontal scaling is required, this decision must be revisited.
- **Constraint:** Any future multi-node deployment must migrate to a distributed Bucket4j backend (e.g., `bucket4j-redis`) and introduce a shared cache. This is documented as a known v1 limitation.
