# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git

---

## 1. Executive Summary

The URL Shortener Service is a Spring Boot REST API that transforms arbitrarily long URLs into compact 6-character alphanumeric short codes. When a user visits the short URL, the service performs an HTTP 302 redirect to the original destination. Each short URL carries a 30-day TTL, after which access returns HTTP 410 Gone. Mappings are persisted in PostgreSQL, making the service stateless and horizontally scalable.

---

## 2. Problem Statement

Long URLs are unwieldy in emails, SMS messages, printed materials, and social media posts where character limits or readability matter. Users need a reliable, programmatic way to:

1. Condense any valid URL into a short, shareable token.
2. Guarantee that the token resolves back to the original URL for a bounded time window.
3. Get a predictable, machine-readable failure signal when a link has expired or never existed.

Without this service, downstream systems must either embed raw long URLs (poor UX) or integrate with third-party shorteners (external dependency, no data sovereignty, no expiry control).

---

## 3. Goals and Non-Goals

### Goals

- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric code and the corresponding short URL.
- Provide a `GET /{code}` endpoint that redirects (HTTP 302) to the original URL.
- Automatically expire short codes 30 days after creation; return HTTP 410 for expired codes.
- Return HTTP 404 for codes that were never created.
- Persist all URL mappings in PostgreSQL with columns: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Guarantee uniqueness of short codes across all active and expired records.
- Generate a new, distinct code every time the same long URL is submitted (no deduplication).
- Provide unit-tested shortening logic and expiry-check logic.

### Non-Goals

- Custom/vanity short codes chosen by the user.
- Analytics, click tracking, or geographic reporting.
- Authentication, authorization, or API key management.
- Bulk URL shortening.
- Short-code deletion or manual expiry by the caller.
- URL preview or safety scanning.
- Admin dashboard or management UI.
- Redirect types other than HTTP 302 (e.g., 301 permanent).
- Support for non-HTTP/HTTPS schemes (e.g., `ftp://`, `mailto:`).
- Rate limiting or abuse prevention (out of scope for v1).

---

## 4. User Stories

| # | User Story |
|---|------------|
| US-1 | As an **API consumer**, I want to submit a long URL and receive a short code so that I can share a compact link in space-constrained contexts. |
| US-2 | As an **API consumer**, I want the short URL included in the response so that I do not need to construct it myself. |
| US-3 | As an **end user**, I want visiting `/<code>` to redirect me immediately to the original URL so that I reach my destination transparently. |
| US-4 | As an **API consumer**, I want a 404 response when I request a code that does not exist so that I can distinguish "never existed" from other error states. |
| US-5 | As an **API consumer**, I want a 410 Gone response when I request an expired code so that I know the link existed but is no longer valid. |
| US-6 | As an **API consumer**, I want to shorten the same long URL multiple times and receive a fresh code each time so that I can distribute independent trackable links. |
| US-7 | As a **developer**, I want unit tests for the shortening logic and expiry check so that I can refactor with confidence. |

---

## 5. Functional Requirements

### 5.1 URL Shortening — `POST /shorten`

| ID | Requirement |
|----|-------------|
| FR-1 | The endpoint MUST accept `Content-Type: application/json` with body `{ "url": "<string>" }`. |
| FR-2 | On success it MUST return HTTP 200 with body `{ "code": "<6-char>", "shortUrl": "http://<host>/<code>" }`. |
| FR-3 | The service MUST generate a random 6-character code using characters `[A-Za-z0-9]` (62-character alphabet). |
| FR-4 | The generated code MUST be unique; if a collision with an existing code (active or expired) is detected, the service MUST retry generation until a unique code is found. |
| FR-5 | The same long URL submitted multiple times MUST produce a different code each submission (no deduplication or caching). |
| FR-6 | `expires_at` MUST be set to `created_at + 30 days` at the moment of insertion. |
| FR-7 | If the `url` field is missing or empty the service MUST return HTTP 400 Bad Request with a descriptive error message. |
| FR-8 | The `shortUrl` in the response MUST reflect the actual host and port of the running service (not a hardcoded value). |

### 5.2 URL Redirection — `GET /{code}`

| ID | Requirement |
|----|-------------|
| FR-9 | Given a valid, non-expired code, the endpoint MUST return HTTP 302 Found with a `Location` header set to the original URL. |
| FR-10 | Given a code that does not exist in the database, the endpoint MUST return HTTP 404 Not Found. |
| FR-11 | Given a code whose `expires_at` timestamp is in the past, the endpoint MUST return HTTP 410 Gone. |
| FR-12 | Expiry MUST be evaluated at request time against the database `expires_at` column; no background job is required. |

### 5.3 Data Model

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGSERIAL / BIGINT | PRIMARY KEY | Auto-incremented surrogate key |
| `code` | VARCHAR(6) | NOT NULL, UNIQUE | The short code |
| `original_url` | TEXT | NOT NULL | The destination long URL |
| `created_at` | TIMESTAMPTZ | NOT NULL | Insertion timestamp (UTC) |
| `expires_at` | TIMESTAMPTZ | NOT NULL | `created_at + 30 days` (UTC) |

### 5.4 Error Responses

| Scenario | HTTP Status | Body |
|----------|-------------|------|
| Missing or blank `url` field | 400 Bad Request | `{ "error": "<message>" }` |
| Code not found | 404 Not Found | `{ "error": "Short URL not found" }` |
| Code expired | 410 Gone | `{ "error": "Short URL has expired" }` |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-1 | `POST /shorten` MUST complete in under 500 ms at the 95th percentile under normal load (single instance, single-node DB). |
| NFR-2 | `GET /{code}` MUST complete in under 200 ms at the 95th percentile under normal load. |
| NFR-3 | The service MUST handle at least 100 concurrent requests without returning 5xx errors on standard hardware (2 vCPU, 2 GB RAM). |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-4 | All database interactions MUST use parameterized queries or a JPA/ORM layer to prevent SQL injection. |
| NFR-5 | The service MUST NOT follow or validate the submitted URL content; it MUST only store and return it. URL validation is limited to syntactic checks (non-empty, valid URI format). |
| NFR-6 | Database credentials MUST be supplied via environment variables or Spring externalized configuration; they MUST NOT be hardcoded in source. |
| NFR-7 | The service MUST NOT expose stack traces or internal error details in HTTP response bodies. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-8 | The service MUST start cleanly if PostgreSQL is reachable and the schema exists. |
| NFR-9 | If PostgreSQL is unreachable, the service MUST return HTTP 503 Service Unavailable rather than an unhandled exception. |
| NFR-10 | Code generation retries on collision MUST be bounded (max 5 retries) to prevent infinite loops; if exhausted, return HTTP 500 with a logged error. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-11 | The service MUST be stateless; session state MUST NOT be stored in application memory. |
| NFR-12 | The `code` column MUST have a database index to support O(log n) lookups at scale. |
| NFR-13 | The service MUST be deployable as multiple replicas behind a load balancer without coordination (shared PostgreSQL is the single source of truth). |

### 6.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-14 | The service MUST emit structured logs (at minimum: request method, path, response status, duration) for each inbound request. |
| NFR-15 | Collision retries MUST be logged at WARN level with the conflicting code value. |

---

## 7. Technical Constraints

| Constraint | Details |
|------------|---------|
| **Framework** | Spring Boot (latest stable 3.x). Must use Spring MVC for REST controllers. |
| **Database** | PostgreSQL. Schema must include the five columns defined in FR section 5.3. Connection via Spring Data JPA or Spring JDBC. |
| **Language** | Java 17+ (LTS). |
| **Build tool** | Maven or Gradle (team preference); dependency management via `pom.xml` or `build.gradle`. |
| **Testing** | JUnit 5 + Mockito for unit tests. Spring Boot Test slice (`@WebMvcTest`, `@DataJpaTest`) for integration-level unit tests. |
| **Randomness** | `java.security.SecureRandom` SHOULD be used for code generation to avoid predictable sequences. |
| **Short code alphabet** | Exactly `[A-Za-z0-9]` — 62 characters, 6 positions → ~56.8 billion possible codes. |
| **Expiry window** | Exactly 30 calendar days from `created_at`. Implementation MUST use `java.time` (not `java.util.Date`). |
| **No external shortener dependencies** | The service must not delegate to third-party URL shortening APIs. |
| **Repository** | Code delivered to the `main` branch of `https://github.com/ashish-chulpar-polestar/url-shortener.git`. |

---

## 8. Success Metrics

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| Code uniqueness | 0 duplicate active codes in production | Unique constraint violation rate in DB logs |
| Redirect success rate | >= 99.9% of valid, non-expired requests return 302 | Application/APM error rate dashboard |
| Expiry accuracy | 100% of codes accessed after `expires_at` return 410 | Automated expiry test in CI |
| Unit test coverage | >= 80% line coverage on shortening and expiry logic | JaCoCo report in CI |
| All acceptance criteria passing | 100% of defined ACs green in CI | CI pipeline pass/fail |
| p95 latency — shorten | < 500 ms | Load test (e.g., k6, Gatling) |
| p95 latency — redirect | < 200 ms | Load test |

---

## 9. Implementation Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R-1 | **Code collision under high write load** — with a 6-char code space (~56.8 B codes) collisions are statistically rare but possible as the table grows. | Low | Medium | Enforce UNIQUE constraint on `code`; implement retry loop (max 5 attempts) on `DataIntegrityViolationException`; log WARN on each retry. |
| R-2 | **Clock skew on expiry evaluation** — if the app server and DB server clocks differ, `expires_at` comparisons may be inconsistent. | Low | Low | Store and compare all timestamps in UTC; use `NOW()` / `CURRENT_TIMESTAMP` from the database side for expiry checks rather than the application clock. |
| R-3 | **PostgreSQL unavailability at startup** — service may fail to boot if the DB is not yet ready (common in containerized environments). | Medium | High | Configure Spring Boot's `spring.datasource` with retry/backoff or use a readiness probe; document Docker Compose `depends_on` with health-check. |
| R-4 | **Unbounded URL length** — very long URLs could exceed column or index limits. | Low | Low | Define `original_url` as `TEXT` (unlimited in PostgreSQL); add input validation to reject URLs exceeding a reasonable limit (e.g., 2048 chars). |
| R-5 | **Security — open redirect abuse** — service blindly redirects to any submitted URL, enabling phishing via trusted short domain. | Medium | Medium | Out of scope for v1, but document known risk; add URL scheme allowlist (`http`, `https`) as a minimal guardrail. |
| R-6 | **Schema migration drift** — manual schema changes without a migration tool lead to environment inconsistency. | Medium | Medium | Use Flyway or Liquibase for schema versioning from day one; include migration script `V1__create_url_mappings.sql` in the repository. |
| R-7 | **Test coverage gaps on expiry boundary** — off-by-one errors in the 30-day expiry window are easy to miss. | Medium | Medium | Write explicit unit tests for exactly-at-expiry, one-second-before, and one-second-after scenarios. |

---

## Appendix A: API Contract Reference

### `POST /shorten`

**Request**
```http
POST /shorten HTTP/1.1
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=true"
}
```

**Response — 200 OK**
```json
{
  "code": "aB3xZ9",
  "shortUrl": "http://localhost:8080/aB3xZ9"
}
```

**Response — 400 Bad Request**
```json
{
  "error": "url must not be blank"
}
```

---

### `GET /{code}`

**Response — 302 Found**
```http
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path?with=query&params=true
```

**Response — 404 Not Found**
```json
{
  "error": "Short URL not found"
}
```

**Response — 410 Gone**
```json
{
  "error": "Short URL has expired"
}
```

---

## Appendix B: Acceptance Criteria Traceability

| Acceptance Criterion | Requirement IDs |
|----------------------|-----------------|
| Short codes are unique and exactly 6 alphanumeric characters | FR-3, FR-4 |
| Duplicate long URLs get a new code each time | FR-5 |
| Expired codes return 410, unknown codes return 404 | FR-10, FR-11 |
| Unit tests for shortening logic and expiry check | FR-3, FR-6, FR-11 (test coverage) |
