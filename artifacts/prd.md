# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-11
**Status:** Draft
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git
**Branch:** main

---

## 1. Executive Summary

This document defines requirements for a URL Shortener Service — a Spring Boot REST API that accepts long URLs and returns short, shareable codes. When a user accesses a short code via `GET /{code}`, the service redirects them to the original URL with an HTTP 302. Short codes are 6-character alphanumeric strings, randomly generated, and automatically expire after 30 days. Expired codes return HTTP 410 Gone; unknown codes return HTTP 404 Not Found. URL-to-code mappings are persisted in PostgreSQL.

---

## 2. Problem Statement

Long URLs are unwieldy in messages, emails, social media posts, and printed materials. They are difficult to remember, prone to line-breaking in plain-text contexts, and expose implementation details of the target system.

**Impact without this service:**
- Users must share verbose, fragile URLs that degrade communication quality.
- There is no centralized control over URL validity or lifecycle — dead links accumulate without a clean expiry mechanism.
- Downstream systems that embed URLs cannot rely on consistent short identifiers.

**Business impact:** Without link management, organizations lose the ability to audit, control, or expire shared links, creating both operational and potential security risks.

---

## 3. Goals and Non-Goals

### Goals
- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric short code.
- Provide a `GET /{code}` endpoint that issues an HTTP 302 redirect to the original URL.
- Automatically expire short codes 30 days after creation; return HTTP 410 Gone for expired codes.
- Return HTTP 404 Not Found for codes that were never created.
- Persist all URL-to-code mappings in PostgreSQL with `id`, `code`, `original_url`, `created_at`, and `expires_at` columns.
- Generate a new code each time a long URL is submitted, even if the URL was previously shortened.
- Provide unit tests covering short code generation logic and expiry checking.

### Non-Goals
- Custom/vanity short codes chosen by the user.
- Analytics, click tracking, or redirect counting.
- Authentication or authorization for creating or managing short URLs.
- Bulk URL shortening.
- URL preview or safety-scanning before redirect.
- Admin UI or management dashboard.
- Code reuse when the same long URL is submitted multiple times.
- Support for codes longer or shorter than 6 characters.
- Manual code deletion or deactivation before the 30-day expiry.
- Rate limiting or abuse prevention (out of scope for this iteration).
- HTTPS termination or TLS configuration.

---

## 4. User Stories

| # | User Story |
|---|-----------|
| US-1 | As a **developer or API consumer**, I want to POST a long URL and receive a short code, so that I can share a compact link in place of the full URL. |
| US-2 | As a **developer or API consumer**, I want the response to include both the raw code and the fully-qualified short URL, so that I can use either form without constructing the URL myself. |
| US-3 | As an **end user**, I want visiting `/<code>` to redirect me immediately to the original URL, so that I reach my destination with minimal friction. |
| US-4 | As an **end user**, I want to receive a clear error (HTTP 404) when I visit a code that has never existed, so that I know the link is invalid. |
| US-5 | As an **end user**, I want to receive a clear error (HTTP 410 Gone) when I visit an expired code, so that I understand the link existed but is no longer valid. |
| US-6 | As a **developer**, I want each submission of the same long URL to produce a new, independent short code, so that multiple senders can track or share links independently without collision. |
| US-7 | As a **developer**, I want short codes to be unique across all active and expired records, so that there is no ambiguity when resolving a code. |

---

## 5. Functional Requirements

### 5.1 Short Code Generation

| ID | Requirement |
|----|-------------|
| FR-1 | The system MUST generate short codes that are exactly 6 characters long. |
| FR-2 | Short codes MUST consist solely of alphanumeric characters (`[A-Za-z0-9]`), giving a character space of 62 possible values per position (62^6 = ~56.8 billion combinations). |
| FR-3 | Short codes MUST be randomly generated; sequential or predictable patterns are not acceptable. |
| FR-4 | Short codes MUST be unique across all records in the database (both active and expired). If a collision is detected during insertion, the system MUST retry generation until a unique code is found. |
| FR-5 | Each `POST /shorten` request MUST produce a new code even if the submitted URL was previously shortened. |

### 5.2 POST /shorten Endpoint

| ID | Requirement |
|----|-------------|
| FR-6 | The endpoint MUST accept `POST` requests to `/shorten`. |
| FR-7 | The request body MUST be JSON with a single field `url` containing the original URL (e.g., `{ "url": "https://..." }`). |
| FR-8 | The `url` field MUST be validated as a well-formed URL. Requests with missing or malformed URLs MUST return HTTP 400 Bad Request with a descriptive error message. |
| FR-9 | On success, the endpoint MUST return HTTP 201 Created with a JSON body: `{ "code": "<6-char code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-10 | The `shortUrl` field MUST be a fully-qualified URL constructed from the server's host and the generated code. |
| FR-11 | The `expires_at` timestamp MUST be set to exactly 30 days after `created_at` at the time of insertion. |

### 5.3 GET /{code} Endpoint

| ID | Requirement |
|----|-------------|
| FR-12 | The endpoint MUST accept `GET` requests to `/{code}`. |
| FR-13 | If the code is found in the database and has not expired (`current_time < expires_at`), the endpoint MUST return HTTP 302 Found with a `Location` header set to the original URL. |
| FR-14 | If the code does not exist in the database, the endpoint MUST return HTTP 404 Not Found. |
| FR-15 | If the code exists but `current_time >= expires_at`, the endpoint MUST return HTTP 410 Gone. |
| FR-16 | The expiry check MUST use server-side time (UTC) at the moment of the request. |

### 5.4 Data Persistence

| ID | Requirement |
|----|-------------|
| FR-17 | All URL mappings MUST be stored in a PostgreSQL database table with at minimum the following columns: `id` (primary key), `code` (unique, varchar(6)), `original_url` (text), `created_at` (timestamp with time zone), `expires_at` (timestamp with time zone). |
| FR-18 | The `code` column MUST have a unique database constraint to prevent duplicate codes at the persistence layer. |
| FR-19 | The application MUST use a database migration tool (e.g., Flyway or Liquibase) or Spring JPA DDL auto-creation to initialize the schema on startup. |

### 5.5 Unit Tests

| ID | Requirement |
|----|-------------|
| FR-20 | Unit tests MUST cover the short code generation logic, asserting that generated codes are exactly 6 characters and consist only of alphanumeric characters. |
| FR-21 | Unit tests MUST cover the expiry-check logic, asserting that active records return a redirect and expired records return 410. |
| FR-22 | Unit tests MUST cover the 404 path for unknown codes. |
| FR-23 | Unit tests MUST be runnable with `./mvnw test` (or equivalent) without requiring a live database (use mocking or an in-memory store). |

---

## 6. Non-Functional Requirements

### 6.1 Performance
- `POST /shorten` MUST respond within **200ms** at p99 under normal single-instance load.
- `GET /{code}` MUST respond within **100ms** at p99 under normal single-instance load (database read + redirect).
- The `code` column MUST be indexed to ensure O(log n) lookup performance.

### 6.2 Security
- The service MUST validate that submitted URLs are well-formed to prevent injection of malicious or non-URL data into the database.
- The service MUST NOT follow or pre-fetch the submitted URL — it accepts and stores it as-is.
- The service MUST NOT expose internal error stack traces in HTTP responses; error responses MUST use standardized JSON error bodies.
- Short codes MUST be randomly generated (not sequentially guessable) to prevent trivial enumeration of all stored URLs.

### 6.3 Reliability and Availability
- The application MUST start cleanly and fail fast (exit with a non-zero code) if the PostgreSQL connection cannot be established at startup.
- The application MUST handle database constraint violations (duplicate code on insert) gracefully by retrying with a new code rather than returning a 5xx error.
- The service MUST return well-formed JSON error bodies (not empty bodies or HTML) for all 4xx and 5xx responses.

### 6.4 Scalability
- The schema and code design MUST support horizontal scaling (multiple instances) since code uniqueness is enforced at the database level via a unique constraint, not in-application state.
- No in-memory caches or local state MUST be required for correctness; all state lives in PostgreSQL.

### 6.5 Observability
- The application MUST emit structured logs for each `POST /shorten` (including generated code, redacted URL, and expiry) and each `GET /{code}` (outcome: redirect, 404, or 410).
- Spring Boot Actuator health endpoint (`/actuator/health`) SHOULD be enabled and include a database connectivity check.

---

## 7. Technical Constraints

| Constraint | Detail |
|-----------|--------|
| **Framework** | Spring Boot (latest stable 3.x). REST endpoints implemented using Spring MVC (`@RestController`). |
| **Language** | Java 17+ (LTS). |
| **Database** | PostgreSQL (version 14+). Spring Data JPA or Spring JDBC for persistence. |
| **Build tool** | Maven (`pom.xml` with Maven Wrapper `mvnw`). |
| **Code length** | Exactly 6 alphanumeric characters — this is a hard constraint; changing it would break existing stored codes. |
| **Expiry window** | Exactly 30 days — stored in `expires_at` column; cannot be configured per-request in this iteration. |
| **No auth layer** | The API is unauthenticated in this iteration; any consumer can create short links. |
| **Single database** | No caching tier (Redis, etc.) in scope; all reads hit PostgreSQL. |
| **Testing** | JUnit 5 + Mockito for unit tests. Integration tests (if added) may use H2 in-memory or Testcontainers for PostgreSQL. |

---

## 8. Success Metrics

| Metric | Target |
|--------|--------|
| **Code uniqueness** | 0 duplicate codes in the database under any load scenario. |
| **Code format compliance** | 100% of generated codes are exactly 6 alphanumeric characters. |
| **Redirect accuracy** | 100% of active-code `GET` requests result in correct HTTP 302 to the original URL. |
| **Expiry enforcement** | 100% of requests for expired codes return HTTP 410 (not 302 or 404). |
| **Unknown code handling** | 100% of requests for non-existent codes return HTTP 404. |
| **Test coverage** | Unit test coverage for code generation and expiry logic >= 80% line coverage. |
| **Build success** | `./mvnw test` passes with 0 failing tests in CI. |
| **Latency** | p99 `GET /{code}` latency < 100ms under load testing at 100 RPS on a single instance. |
| **Error response format** | 0 plain-text or HTML error responses; all errors return structured JSON. |

---

## 9. Implementation Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|-----------|
| R-1 | **Code collision at high volume** — With 62^6 ~56.8B combinations, collision probability is negligible at low scale, but non-zero at high insertion rates. | Low | Medium | Enforce a `UNIQUE` constraint on `code` in PostgreSQL. On `DataIntegrityViolationException`, catch and retry generation up to N times (e.g., 5 retries) before returning a 500. |
| R-2 | **URL validation too strict or too lenient** — Overly strict validation rejects legitimate URLs; too lenient allows garbage data. | Medium | Medium | Use `java.net.URL` or a well-tested library (e.g., Apache Commons Validator) for URL format validation. Define accepted schemes (`http`, `https`) explicitly. |
| R-3 | **Clock skew in expiry checks** — If multiple instances run with different system clocks, expiry behavior may be inconsistent. | Low | Low | Use database server time (e.g., `NOW()` in PostgreSQL) for `created_at` and `expires_at` inserts, or use UTC consistently across all instances via `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`. |
| R-4 | **Schema migration not applied in CI/CD** — Tests against a live database fail if DDL is not applied before the test run. | Medium | High | Use Flyway or Liquibase to manage schema migrations; ensure migrations run on application startup and are checked into version control. |
| R-5 | **`shortUrl` host construction incorrect in proxied/containerized environments** — `HttpServletRequest.getServerName()` may return an internal hostname behind a load balancer or reverse proxy. | Medium | Medium | Make the base URL configurable via `application.properties` (e.g., `app.base-url=https://short.example.com`) so the host used in `shortUrl` is explicitly set, not derived from the request. |
| R-6 | **Expired records accumulate** — Over time, the `url_mappings` table grows with expired rows, degrading query performance. | Low (short term) | Medium (long term) | Add a database index on `expires_at`. Plan a scheduled cleanup job (e.g., `@Scheduled` or a PostgreSQL cron job) for a future iteration to purge expired rows. |
| R-7 | **Open redirect abuse** — The service redirects to any stored URL without safety checks, enabling use as an open redirect for phishing. | Medium | High | Out of scope for v1, but documented. Future iteration should add URL allow-listing or integration with a URL safety API (e.g., Google Safe Browsing). |

---

## Appendix A: API Contract Summary

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=here"
}
```

**Response — 201 Created**
```json
{
  "code": "aB3xY9",
  "shortUrl": "http://localhost:8080/aB3xY9"
}
```

**Response — 400 Bad Request**
```json
{
  "error": "Invalid URL",
  "message": "The provided URL is not a valid URL."
}
```

---

### GET /{code}

**Response — 302 Found (active code)**
```
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path?with=query&params=here
```

**Response — 404 Not Found (unknown code)**
```json
{
  "error": "Not Found",
  "message": "No short URL found for code: aB3xY9"
}
```

**Response — 410 Gone (expired code)**
```json
{
  "error": "Gone",
  "message": "The short URL for code aB3xY9 has expired."
}
```

---

## Appendix B: Database Schema

```sql
CREATE TABLE url_mappings (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL UNIQUE,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_url_mappings_code ON url_mappings (code);
CREATE INDEX idx_url_mappings_expires_at ON url_mappings (expires_at);
```

---

*End of Document*
