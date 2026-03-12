# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Author:** Senior Product Manager

---

## 1. Executive Summary

This document defines the requirements for a URL Shortener Service — a Spring Boot REST API that accepts long URLs and returns short alphanumeric codes. When a user visits the short URL, they are redirected to the original destination. Short codes are exactly 6 alphanumeric characters, randomly generated, and expire after 30 days. Mappings are persisted in PostgreSQL. The service exposes two endpoints: one for shortening a URL and one for resolving and redirecting. This is a net-new greenfield service with no existing codebase.

---

## 2. Problem Statement

Long URLs are unwieldy to share, embed in documents, or include in communications. They obscure the destination, break across line wraps, and are error-prone when typed manually. There is no internal service today that provides short, opaque, time-limited aliases for arbitrary URLs.

**Impact:**
- Users are forced to share raw long URLs, degrading readability and usability in written communications, social media, and printed materials.
- Without expiry enforcement, stale links accumulate indefinitely, creating maintenance overhead and potential misdirection risk.
- Without a managed service, teams resort to ad-hoc solutions (e.g., public third-party shorteners) that may leak internal URLs to external analytics providers.

---

## 3. Goals and Non-Goals

### Goals

- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric short code.
- Provide a `GET /{code}` endpoint that resolves the code and issues an HTTP 302 redirect to the original URL.
- Automatically expire short URLs 30 days after creation; return HTTP 410 Gone for expired codes.
- Return HTTP 404 Not Found for codes that do not exist in the database.
- Guarantee uniqueness of all active short codes.
- Persist all URL mappings in PostgreSQL with columns: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Provide unit test coverage for shortening logic and expiry checking.

### Non-Goals

- Custom or vanity short codes (user-supplied codes) are out of scope.
- Analytics, click tracking, or redirect counting are out of scope.
- Authentication or authorization of who may shorten URLs is out of scope.
- A frontend or browser-based UI is out of scope.
- Bulk URL shortening (multiple URLs in a single request) is out of scope.
- URL preview or safety scanning is out of scope.
- Admin endpoints for managing or deleting codes are out of scope.
- Rate limiting is out of scope for v1.
- Caching or CDN integration is out of scope for v1.

---

## 4. User Stories

**US-01 — Shorten a URL**
As an API consumer, I want to POST a long URL and receive a short code so that I can share a concise link instead of the full URL.

**US-02 — Redirect via short code**
As an end user, I want to visit a short URL and be immediately redirected to the original destination so that I can reach the intended page without knowing the full URL.

**US-03 — Handle unknown short codes gracefully**
As an end user, I want to receive a clear 404 Not Found response when I visit a short code that does not exist so that I understand the link is invalid rather than encountering a confusing error.

**US-04 — Handle expired short codes gracefully**
As an end user, I want to receive a clear 410 Gone response when I visit a short code that has expired so that I understand the link existed previously but is no longer valid.

**US-05 — Generate a new code for repeated long URLs**
As an API consumer, I want each POST of the same long URL to produce a distinct new short code so that I can generate multiple independent short links pointing to the same destination.

**US-06 — Verify code uniqueness**
As an API consumer, I want confidence that the short code I receive has never been issued to a different URL so that my redirects are always correct.

---

## 5. Functional Requirements

### 5.1 URL Shortening — `POST /shorten`

| ID | Requirement |
|----|-------------|
| FR-01 | The endpoint MUST accept `Content-Type: application/json` with a body of `{ "url": "<string>" }`. |
| FR-02 | The `url` field MUST be validated as a syntactically well-formed HTTP or HTTPS URL. Requests with a missing or malformed `url` MUST return HTTP 400 Bad Request with a descriptive error message. |
| FR-03 | On success, the endpoint MUST return HTTP 200 OK with a JSON body: `{ "code": "<6-char code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-04 | The generated short code MUST be exactly 6 characters in length. |
| FR-05 | The short code MUST consist only of alphanumeric characters (`[A-Za-z0-9]`). |
| FR-06 | Short codes MUST be randomly generated (not derived from the URL content or a sequential counter). |
| FR-07 | Each call to `POST /shorten` — including calls with the same long URL — MUST produce a new, independent short code. Deduplication of identical URLs is explicitly not performed. |
| FR-08 | The generated code MUST be unique across all non-expired and expired records in the database. If a collision is detected during generation, the service MUST retry generation until a unique code is found. |
| FR-09 | The record persisted to PostgreSQL MUST include: `id` (primary key), `code` (unique index), `original_url`, `created_at` (timestamp of creation), `expires_at` (`created_at` + 30 days). |
| FR-10 | The `shortUrl` returned in the response MUST use the host as seen by the service (configurable via `server.host` or equivalent property). |

### 5.2 URL Resolution — `GET /{code}`

| ID | Requirement |
|----|-------------|
| FR-11 | The endpoint MUST look up the provided `code` in the database (case-sensitive match). |
| FR-12 | If the code is not found in the database, the endpoint MUST return HTTP 404 Not Found. |
| FR-13 | If the code is found but `expires_at` is in the past (strictly less than the current UTC timestamp), the endpoint MUST return HTTP 410 Gone. |
| FR-14 | If the code is found and not expired, the endpoint MUST return HTTP 302 Found with a `Location` header set to the `original_url`. |
| FR-15 | The expiry check MUST use server-side UTC time; client-supplied timestamps MUST NOT be trusted. |

### 5.3 Data Model

| ID | Requirement |
|----|-------------|
| FR-16 | The PostgreSQL table `short_urls` MUST have the following columns: `id BIGSERIAL PRIMARY KEY`, `code VARCHAR(6) NOT NULL UNIQUE`, `original_url TEXT NOT NULL`, `created_at TIMESTAMP WITH TIME ZONE NOT NULL`, `expires_at TIMESTAMP WITH TIME ZONE NOT NULL`. |
| FR-17 | A unique index MUST be enforced on `code` at the database level to serve as the final guarantee against duplicates. |
| FR-18 | Schema migrations MUST be managed via a migration tool (e.g., Flyway or Liquibase) to ensure reproducible database setup. |

### 5.4 Error Handling

| ID | Requirement |
|----|-------------|
| FR-19 | All error responses MUST return `Content-Type: application/json` with a body containing at minimum `{ "error": "<human-readable message>" }`. |
| FR-20 | HTTP 400 — malformed or missing `url` field. |
| FR-21 | HTTP 404 — code not found in the database. |
| FR-22 | HTTP 410 — code found but expired. |
| FR-23 | Unexpected server errors MUST return HTTP 500 Internal Server Error without leaking stack traces or internal details to the caller. |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-01 | `POST /shorten` MUST respond in under 300 ms at the 95th percentile under normal load (up to 100 concurrent requests). |
| NFR-02 | `GET /{code}` MUST respond in under 100 ms at the 95th percentile under normal load, given a warm database connection pool. |
| NFR-03 | Code generation collision retry loops MUST complete within 5 attempts for a namespace utilization below 50% (approximately 900 million possible codes). |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-04 | All database queries MUST use parameterized statements or ORM-managed queries to prevent SQL injection. |
| NFR-05 | The `original_url` field MUST be stored and returned verbatim; the service MUST NOT follow or validate the destination URL beyond structural well-formedness. |
| NFR-06 | The service MUST NOT include internal system paths, hostnames, or stack traces in any API response. |
| NFR-07 | The `code` path parameter MUST be sanitized/validated to match `[A-Za-z0-9]{6}` before database lookup to prevent injection via path variable. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-08 | The service MUST handle database connection failures gracefully and return HTTP 503 Service Unavailable rather than hanging indefinitely. |
| NFR-09 | Database connection pool MUST be configured with a maximum connection wait timeout to avoid thread exhaustion under load. |
| NFR-10 | The service MUST be stateless at the application tier to support horizontal scaling behind a load balancer. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-11 | The PostgreSQL schema MUST include an index on `code` to ensure O(log n) lookup regardless of table size. |
| NFR-12 | The application MUST be deployable as a single JAR (Spring Boot fat jar) to facilitate containerization. |

### 6.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-13 | The service MUST emit structured logs (JSON format recommended) including request method, path, response status, and latency for every request. |
| NFR-14 | Spring Boot Actuator MUST be enabled with at minimum `/actuator/health` and `/actuator/info` endpoints for liveness/readiness probing. |

---

## 7. Technical Constraints

| ID | Constraint |
|----|------------|
| TC-01 | The implementation language is Java; the framework is Spring Boot (minimum version 3.x recommended for native support and security patches). |
| TC-02 | The data store is PostgreSQL; no other database engine is supported for v1. |
| TC-03 | The build tool is Maven or Gradle (team preference); a standard Spring Initializr project layout MUST be followed. |
| TC-04 | The short code is fixed at 6 alphanumeric characters — this is not configurable in v1. |
| TC-05 | The expiry window is fixed at 30 days from creation — this is not configurable per-request in v1. |
| TC-06 | The service is a plain REST API; no GraphQL, gRPC, or WebSocket interfaces are in scope. |
| TC-07 | No external URL-shortening libraries may be used; all code generation and expiry logic must be implemented in-house. |
| TC-08 | Dependencies must be declared explicitly in `pom.xml` or `build.gradle`; no transitive-only reliance on undeclared dependencies. |
| TC-09 | The repository is hosted at `https://github.com/ashish-chulpar-polestar/url-shortener.git` on branch `main`. |

---

## 8. Success Metrics

| ID | Metric | Target |
|----|--------|--------|
| SM-01 | Unit test coverage for shortening logic (code generation, uniqueness retry, 6-char alphanumeric constraint) | 100% line coverage |
| SM-02 | Unit test coverage for expiry check logic (expired vs. active, boundary conditions) | 100% line coverage |
| SM-03 | All functional requirements (FR-01 through FR-23) verified by automated integration or unit tests | Pass rate: 100% |
| SM-04 | Zero known SQL injection vulnerabilities (verified by code review and SAST scan) | 0 critical/high findings |
| SM-05 | `GET /{code}` p95 latency in local integration test environment | < 100 ms |
| SM-06 | `POST /shorten` p95 latency in local integration test environment | < 300 ms |
| SM-07 | Collision probability: distinct codes returned for 1,000 consecutive `POST /shorten` calls to the same URL | 1,000 unique codes (0 duplicates) |
| SM-08 | Expired code correctly returns 410 in automated test (code created with `expires_at` set to past timestamp) | Pass |
| SM-09 | Unknown code correctly returns 404 in automated test | Pass |

---

## 9. Implementation Risks

### Risk 1 — Short code collision under high throughput

**Description:** The 6-character alphanumeric space has ~2.18 billion possible codes. Under sustained high write volume, collision probability on random generation increases, causing retry loops that degrade `POST /shorten` latency.

**Likelihood:** Low for initial deployment volumes; medium at scale.

**Mitigation:**
- Enforce a database-level unique constraint on `code` so collisions are caught at insert, not just at generation time.
- Implement an in-application retry loop (up to 5 attempts) before returning HTTP 500.
- Monitor collision rate via application metrics; plan migration to 8-character codes before namespace utilization exceeds 10%.

---

### Risk 2 — Expired records accumulating in PostgreSQL

**Description:** Records are never deleted after expiry. Over time, the `short_urls` table grows unboundedly, degrading query performance and increasing storage costs.

**Likelihood:** High over long operational lifetime.

**Mitigation:**
- Add a PostgreSQL index on `expires_at` to efficiently filter expired rows.
- Plan a background cleanup job (e.g., a scheduled Spring `@Scheduled` task or a PostgreSQL `pg_cron` job) to purge records where `expires_at < NOW() - INTERVAL '7 days'` as a v1.1 follow-on.
- Document the cleanup strategy in the operational runbook even if not implemented in v1.

---

### Risk 3 — `shortUrl` host misconfiguration

**Description:** The `shortUrl` field in the `POST /shorten` response must contain the correct public-facing hostname. If the service is deployed behind a reverse proxy or load balancer, the internally resolved hostname may differ from the public URL.

**Likelihood:** Medium (common in containerized and cloud deployments).

**Mitigation:**
- Expose a configurable `app.base-url` property (e.g., `http://short.example.com`) that is used to construct the `shortUrl` value, decoupled from Spring's inferred server address.
- Document this as a required configuration item in the README and deployment guide.

---

### Risk 4 — Database unavailability at startup or runtime

**Description:** If PostgreSQL is unavailable when the service starts, or becomes unavailable during operation, unhandled exceptions may surface as 500 errors with internal details.

**Likelihood:** Low in production with managed databases; medium in local/CI environments.

**Mitigation:**
- Configure Spring Boot's `spring.datasource.hikari.*` pool with explicit connection timeout and validation settings.
- Add a global exception handler (`@ControllerAdvice`) that maps `DataAccessException` and similar to HTTP 503 with a sanitized error body.
- Use Spring Boot Actuator's `/actuator/health` to expose database connectivity as a health check for orchestration platforms (Kubernetes liveness/readiness probes).

---

### Risk 5 — Lack of input validation enabling open redirect abuse

**Description:** If `original_url` is not validated for structural well-formedness, an attacker could submit a `javascript:` URI or other non-HTTP scheme, causing the redirect endpoint to issue a potentially harmful `Location` header.

**Likelihood:** Medium (common attack vector on redirect services).

**Mitigation:**
- Validate that submitted URLs conform to `http://` or `https://` schemes at the `POST /shorten` layer; reject all other schemes with HTTP 400.
- Use Java's `java.net.URI` or a well-tested URL validation library (e.g., Apache Commons Validator) rather than regex-only validation.

---

*End of Document*
