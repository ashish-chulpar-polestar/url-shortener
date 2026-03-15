# ADR-003: Bucket4j Token-Bucket Rate Limiting via Servlet Filter

**Status:** Accepted
**Date:** 2026-03-15
**Feature:** MTI Scores API - Test1

## Context

The PRD requires rate limiting at 100 requests per minute per API key (implemented here as per client IP). We needed a rate-limiting approach that: (a) operates before Spring MVC's dispatcher (to reject over-limit requests early), (b) is in-memory and requires no external infrastructure, and (c) supports the token-bucket algorithm for smooth rate control. Options considered included Spring Security's rate-limiting support, a custom counter with a scheduled reset, and Bucket4j.

## Decision

Use **Bucket4j 8.7.0** (`com.bucket4j:bucket4j-core:8.7.0`) with **Caffeine 3.1.8** as the in-memory bucket store. Rate limiting is implemented in `RateLimitFilter` (a `jakarta.servlet.Filter` annotated `@Order(2)`) which runs after `RequestIdFilter` (`@Order(1)`). A `ConcurrentHashMap<String, Bucket>` bean defined in `RateLimitConfig` stores one `Bucket` per client IP. Each bucket uses `Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)))` — 100-token capacity, greedily refilled at 100 tokens per minute. When `bucket.tryConsume(1)` returns false, the filter writes a JSON 429 response directly (bypassing Spring MVC's message converters) with `error_code="ERR_106"`.

## Consequences

- Token-bucket allows short bursts up to 100 requests instantly before throttling, which is more user-friendly than a fixed-window counter.
- The `ConcurrentHashMap` grows unboundedly as new client IPs are seen; for a production deployment behind a load balancer, this should be replaced with a `Caffeine Cache` with TTL-based eviction to cap memory usage.
- Rate limiting is keyed on `HttpServletRequest.getRemoteAddr()`. Behind a reverse proxy or load balancer, all requests will appear to come from the proxy IP, defeating per-client rate limiting. In that case, the filter must be updated to read the `X-Forwarded-For` header.
- `@Order(2)` ensures the request ID is already set on the request (by `RequestIdFilter` at `@Order(1)`) when the rate-limit filter needs to include it in the 429 body.
- No external Redis or Hazelcast cluster is required, keeping the deployment simple for the current scale requirements.
