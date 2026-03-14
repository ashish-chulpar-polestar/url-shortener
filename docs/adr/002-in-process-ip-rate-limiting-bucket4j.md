# ADR-002: In-Process IP-Based Rate Limiting with Bucket4j and Caffeine

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD specifies rate limiting at "100 requests per minute per API key", but defines no API key authentication mechanism anywhere in the specification. The service must still enforce a rate limit to protect against abuse. Three options were considered:

1. API key authentication with per-key limits stored in Redis — requires introducing authentication infrastructure not scoped by the PRD.
2. In-process IP-based rate limiting using Bucket4j with a Caffeine `LoadingCache` — no external dependencies, deployable as a single JAR.
3. Delegating rate limiting to an API gateway or reverse proxy (e.g., nginx, AWS API Gateway) — keeps the application stateless but requires infrastructure coordination outside the codebase.

## Decision

Use Bucket4j 8.10.1 with the token-bucket algorithm, backed by a Caffeine `LoadingCache<String, Bucket>` keyed by client IP address (`HttpServletRequest.getRemoteAddr()`). The rate limit threshold is externalized to the configuration property `mti.rate-limit.requests-per-minute` (default 100) so it can be overridden per environment. The `LoadingCache` expires idle buckets after 2 minutes to prevent unbounded memory growth. The limit is enforced in `RateLimitFilter` at `@Order(2)`, running after `RequestIdFilter` (`@Order(1)`) so the `X-Request-Id` header is present on 429 responses.

## Consequences

- No Redis, no external infrastructure required for MVP; the service remains a single deployable unit.
- Rate limit state is lost on application restart — acceptable for MVP, not suitable for a multi-instance production deployment without a shared store.
- Using `request.getRemoteAddr()` means the rate limit applies to the immediately connecting IP. In production behind a load balancer or reverse proxy, `X-Forwarded-For` should be trusted instead; this requires a configuration change or a custom IP extraction strategy and is documented as a known limitation.
- The PRD's "per API key" intent is not fulfilled for MVP; this ADR acknowledges the gap and notes that API key authentication with per-key Redis buckets is the production path.
- Setting `mti.rate-limit.requests-per-minute=10000` in `application-test.yml` effectively disables rate limiting in tests, preventing flaky test failures from bucket exhaustion.
