# Product Requirements Document: URL Shortener Service

**Project:** URL Shortener Service
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git
**Branch:** main
**Date:** 2026-03-12
**Status:** Draft

---

## 1. Executive Summary

The URL Shortener Service is a new Spring Boot REST API that converts long, unwieldy URLs into compact 6-character alphanumeric codes. Users submit a URL and receive a short link in return; visiting that short link redirects them to the original destination. Codes expire automatically after 30 days. The service stores all mappings in a PostgreSQL database and returns appropriate HTTP status codes for expired (410 Gone) and unknown (404 Not Found) codes.

This is a greenfield project: the repository currently contains no application code. The full service must be built from scratch.

---

## 2. Problem Statement

Long URLs are impractical to share in messages, emails, printed materials, and character-limited contexts such as social media. They are also opaque — the destination is not obvious at a glance — and can break when copied across line boundaries.

Without a URL shortener, users must share full, lengthy URLs, leading to:

- Poor user experience in communication channels with character limits
- Higher rates of link-breakage from copy-paste errors
- Difficulty tracking or managing shared links
- No natural expiry mechanism, leaving stale links live indefinitely

The URL Shortener Service addresses these pain points by providing a simple, reliable API that produces short, predictable, and time-bounded links.

---

## 3. Goals and Non-Goals

### Goals

- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric short code.
- Provide a `GET /{code}` endpoint that redirects the caller to the original URL via HTTP 302.
- Return HTTP 410 Gone when a code exists in the database but has expired (past 30-day TTL).
- Return HTTP 404 Not Found when a code does not exist in the database.
- Ensure every generated short code is unique at the time of creation.
- Persist all URL mappings in a PostgreSQL database with columns: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Provide unit tests covering the shortening logic and expiry-check logic.
- Accept any valid URL submitted to the shorten endpoint (duplicate long URLs receive new, independent codes).

### Non-Goals

- Custom or user-specified short codes (vanity URLs) — not in scope.
- Analytics, click-tracking, or redirect-count reporting.
- Authentication, authorization, or rate limiting.
- URL validation beyond basic syntactic checks (e.g., SSRF protection, domain allow-listing).
- A frontend or browser-based UI.
- Bulk URL shortening endpoints.
- Code deletion or manual deactivation by the user.
- Changing the expiry period dynamically per request.
- High-availability clustering, caching layers, or CDN integration.
- Internationalized domain names (IDN) or non-ASCII URL handling beyond standard encoding.

---

## 4. User Stories

| # | User Story |
|---|------------|
| US-1 | As an API consumer, I want to submit a long URL and receive a short code so that I can share a compact link. |
| US-2 | As an API consumer, I want to visit a short URL and be redirected to the original destination so that I can reach the intended content without knowing the full URL. |
| US-3 | As an API consumer, I want to receive a 404 response when I visit an unknown short code so that I can detect invalid links programmatically. |
| US-4 | As an API consumer, I want to receive a 410 Gone response when I visit an expired short code so that I can distinguish between a link that never existed and one that has expired. |
| US-5 | As an API consumer, I want duplicate long URL submissions to each produce a new, independent short code so that I can generate fresh links for the same destination without conflict. |
| US-6 | As a developer, I want the API to return a JSON response from `POST /shorten` containing both the raw code and a fully qualified short URL so that I can display either form to end users. |

---

## 5. Functional Requirements

### 5.1 URL Shortening — `POST /shorten`

| ID | Requirement |
|----|-------------|
| FR-1.1 | The endpoint MUST accept a JSON request body with a single field `url` containing the target long URL. |
| FR-1.2 | The endpoint MUST respond with HTTP 200 and a JSON body containing `code` (the 6-character short code) and `shortUrl` (the fully qualified short URL, e.g., `http://<host>/<code>`). |
| FR-1.3 | The generated short code MUST be exactly 6 characters long and consist only of alphanumeric characters (`[A-Za-z0-9]`). |
| FR-1.4 | Short codes MUST be randomly generated; sequential or predictable patterns are not acceptable. |
| FR-1.5 | Each generated code MUST be unique across all non-expired and expired records in the database at the time of insertion. If a collision is detected, a new code MUST be generated and retried until a unique code is found. |
| FR-1.6 | Submitting the same long URL multiple times MUST produce a distinct new code each time; no deduplication of long URLs is performed. |
| FR-1.7 | The `expires_at` value MUST be set to exactly 30 days after `created_at` at the time of record insertion. |
| FR-1.8 | If the `url` field is missing or empty, the endpoint MUST return HTTP 400 Bad Request with a descriptive error message. |
| FR-1.9 | The `shortUrl` field in the response MUST use the same scheme and host as the incoming request (or a configurable base URL). |

### 5.2 URL Redirection — `GET /{code}`

| ID | Requirement |
|----|-------------|
| FR-2.1 | The endpoint MUST look up the provided `{code}` in the database. |
| FR-2.2 | If the code is found and `expires_at` is in the future, the endpoint MUST respond with HTTP 302 Found and a `Location` header set to the original URL. |
| FR-2.3 | If the code is found but `expires_at` is in the past or equal to the current time, the endpoint MUST respond with HTTP 410 Gone. |
| FR-2.4 | If the code is not found in the database, the endpoint MUST respond with HTTP 404 Not Found. |
| FR-2.5 | The expiry check MUST use the database `expires_at` timestamp compared against the current server time at the moment of the request. |

### 5.3 Data Model

| ID | Requirement |
|----|-------------|
| FR-3.1 | The PostgreSQL table MUST be named `short_urls` (or equivalent, as defined by the schema migration). |
| FR-3.2 | The table MUST contain the columns: `id` (bigint, primary key, auto-increment), `code` (varchar(6), unique, not null), `original_url` (text, not null), `created_at` (timestamp with time zone, not null), `expires_at` (timestamp with time zone, not null). |
| FR-3.3 | The `code` column MUST have a unique constraint or unique index to enforce code uniqueness at the database level. |
| FR-3.4 | Database schema MUST be managed via a migration tool compatible with Spring Boot (e.g., Flyway or Liquibase). |

### 5.4 Testing

| ID | Requirement |
|----|-------------|
| FR-4.1 | Unit tests MUST cover the short-code generation logic, including verification that generated codes match the `[A-Za-z0-9]{6}` pattern. |
| FR-4.2 | Unit tests MUST cover the expiry-check logic, verifying that a record with `expires_at` in the past returns 410 and one with `expires_at` in the future returns 302. |
| FR-4.3 | Unit tests MUST verify that the 404 response is returned for unknown codes. |
| FR-4.4 | Unit tests MUST verify that duplicate long URL submissions produce distinct codes. |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-1.1 | `GET /{code}` MUST respond within 200 ms at the 95th percentile under normal operating load (single-instance deployment). |
| NFR-1.2 | `POST /shorten` MUST respond within 500 ms at the 95th percentile under normal operating load. |
| NFR-1.3 | The `code` column MUST be indexed to ensure O(log n) or better lookup performance. |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-2.1 | All database queries MUST use parameterized statements or an ORM (e.g., Spring Data JPA) to prevent SQL injection. |
| NFR-2.2 | The service MUST NOT expose internal stack traces or database error details in HTTP responses. |
| NFR-2.3 | The `original_url` stored and returned in the `Location` header MUST be the value as submitted — no server-side URL rewriting — to avoid open redirect abuse beyond what the caller provides. |
| NFR-2.4 | The application MUST read database credentials from environment variables or a secrets manager, not from hardcoded values in source code. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-3.1 | The service MUST handle database connection failures gracefully and return HTTP 503 Service Unavailable rather than crashing. |
| NFR-3.2 | Code-collision retry logic MUST include a maximum retry limit (e.g., 10 attempts) to prevent infinite loops; if the limit is exceeded, the service MUST return HTTP 500 with an appropriate error. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-4.1 | The application MUST be stateless so that multiple instances can run behind a load balancer using the shared PostgreSQL database. |
| NFR-4.2 | The database unique constraint on `code` MUST be relied upon as the final collision guard in concurrent multi-instance deployments. |

### 6.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-5.1 | The service MUST emit structured application logs at INFO level for each shorten and redirect operation, including the short code and HTTP status returned. |
| NFR-5.2 | Spring Boot Actuator health endpoint (`/actuator/health`) SHOULD be enabled to expose database connectivity status. |

---

## 7. Technical Constraints

| ID | Constraint |
|----|------------|
| TC-1 | The service MUST be built using **Spring Boot** (latest stable 3.x release recommended). |
| TC-2 | The persistence store MUST be **PostgreSQL**. No other databases are in scope. |
| TC-3 | The build tool MUST be **Maven** or **Gradle** — whichever is initialized with the project. |
| TC-4 | Short codes are fixed at **6 alphanumeric characters**. The length and character set MUST NOT be configurable at runtime. |
| TC-5 | The expiry window is fixed at **30 days**. This value MAY be externalized to application configuration (`application.properties` / `application.yml`) but MUST default to 30 days. |
| TC-6 | The repository is a **greenfield project** — there is no existing application code. The full service must be implemented from scratch. |
| TC-7 | The `GET /{code}` path segment MUST NOT conflict with Spring Boot Actuator endpoints or other well-known paths. Actuator should be mounted under `/actuator`. |
| TC-8 | Java version MUST be 17 or later (required by Spring Boot 3.x). |

---

## 8. Success Metrics

| ID | Metric | Target |
|----|--------|--------|
| SM-1 | All functional requirements implemented | 100% of FR items pass acceptance review |
| SM-2 | Unit test pass rate | 100% of unit tests pass in CI |
| SM-3 | Code uniqueness | Zero duplicate `code` values in the database (enforced by unique constraint) |
| SM-4 | Correct HTTP status codes | `GET /{code}` returns 302, 404, or 410 in all test scenarios with zero regressions |
| SM-5 | Expiry enforcement | 100% of codes past their `expires_at` timestamp return 410; no expired code returns 302 |
| SM-6 | Short code format compliance | 100% of generated codes match `[A-Za-z0-9]{6}` |
| SM-7 | No credential leakage | Zero hardcoded credentials in source code (verified by code review / secret scanning) |

---

## 9. Implementation Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|------------|--------|------------|
| R-1 | **Code collision under high write volume** — With a 6-character alphanumeric space (~56 billion combinations) collisions are statistically unlikely at low scale, but concurrent inserts could still collide. | Low | Medium | Enforce a unique database constraint on `code`. Implement retry logic (up to 10 attempts) in the service layer. Log collision events for monitoring. |
| R-2 | **Clock skew between app server and database** — Expiry checks comparing `NOW()` in Java vs. database time may differ if server clocks are out of sync. | Low | Low | Perform the expiry check in the application layer using the Java system clock (consistent within a single JVM). Alternatively, delegate expiry comparison to a database query predicate. Choose one approach and document it. |
| R-3 | **Database migration failures on startup** — If Flyway/Liquibase migration scripts contain errors, the application will fail to start. | Low | High | Write and test migration scripts against a local PostgreSQL instance before committing. Include migrations in the CI test suite using Testcontainers or an in-memory-compatible migration test. |
| R-4 | **Open redirect abuse** — The service will redirect any submitted URL without validation, which could be used to redirect users to malicious sites. | Medium | Medium | Document the risk. As a future enhancement, consider domain allow-listing or Google Safe Browsing API integration. For the initial scope, ensure no server-side transformation of the stored URL occurs. |
| R-5 | **Unbounded table growth** — Expired records are never deleted, causing the `short_urls` table to grow indefinitely. | Medium | Low | Accept for initial scope. Document a recommended scheduled job (e.g., a Spring `@Scheduled` task or a database cron) to purge records where `expires_at < NOW()` as a follow-up enhancement. |
| R-6 | **`shortUrl` base URL misconfiguration** — If the host used to construct `shortUrl` in the response does not match the deployed hostname, returned short URLs will not resolve. | Low | Medium | Externalize the base URL to a required configuration property (`app.base-url`). Fail fast on startup if the property is not set. |

---

## Appendix: API Contract Reference

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?query=value"
}
```

**Response — 200 OK**
```json
{
  "code": "aB3xYz",
  "shortUrl": "http://localhost:8080/aB3xYz"
}
```

**Response — 400 Bad Request** (missing or empty `url` field)
```json
{
  "error": "Field 'url' is required and must not be empty."
}
```

---

### GET /{code}

| Scenario | HTTP Status | Behavior |
|----------|-------------|----------|
| Code exists and not expired | 302 Found | `Location: <original_url>` |
| Code exists but expired | 410 Gone | Empty body or error message |
| Code does not exist | 404 Not Found | Empty body or error message |

---

## Appendix: Database Schema

```sql
CREATE TABLE short_urls (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL UNIQUE,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
```
