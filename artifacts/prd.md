# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Author:** Product Management
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Goals and Non-Goals](#3-goals-and-non-goals)
4. [User Stories](#4-user-stories)
5. [Functional Requirements](#5-functional-requirements)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Technical Constraints](#7-technical-constraints)
8. [Success Metrics](#8-success-metrics)
9. [Implementation Risks](#9-implementation-risks)

---

## 1. Executive Summary

This document describes the requirements for a **URL Shortener Service** — a Spring Boot REST API that converts long URLs into compact 6-character alphanumeric codes. Users submit a long URL and receive a shortened version. Visiting the short URL redirects the user to the original destination via an HTTP 302 redirect. Short codes expire after 30 days; expired codes return HTTP 410 Gone, and unknown codes return HTTP 404 Not Found. URL mappings are persisted in a PostgreSQL database.

This is a greenfield service with no pre-existing codebase. The scope is a focused, single-responsibility microservice.

---

## 2. Problem Statement

Long URLs are unwieldy: they are difficult to share verbally, exceed character limits in messaging systems, and degrade readability in documents and emails. Users need a reliable way to convert any long URL into a short, opaque code that can be shared easily and that automatically redirects to the original destination.

Without a URL shortener:
- Links become unmanageable in constrained environments (SMS, social media, printed material).
- There is no mechanism to detect whether a shared link is still valid or has become stale.
- Users have no programmatic API to integrate link shortening into their own applications.

**Impact:** Reduced friction in sharing URLs, improved readability, and programmatic control over link lifecycle through expiration.

---

## 3. Goals and Non-Goals

### Goals

- Provide a REST API endpoint (`POST /shorten`) that accepts a long URL and returns a unique 6-character alphanumeric short code.
- Provide a redirect endpoint (`GET /{code}`) that performs an HTTP 302 redirect to the original URL.
- Generate a new short code on every submission, even for duplicate long URLs.
- Expire short codes automatically after 30 days from creation.
- Return HTTP 410 Gone for expired codes and HTTP 404 Not Found for unknown codes.
- Persist all URL mappings in PostgreSQL with fields: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Provide unit tests covering the shortening logic and expiry-check logic.

### Non-Goals

- Custom short codes (user-defined aliases) are out of scope.
- Click analytics, tracking, or reporting on redirect counts are out of scope.
- Authentication or authorization of any kind is out of scope.
- Rate limiting or abuse prevention (e.g., per-IP throttling) is out of scope for this version.
- Bulk URL shortening (batch endpoint) is out of scope.
- URL preview or safety-check functionality is out of scope.
- Admin UI or dashboard is out of scope.
- Code deletion or manual deactivation by the user is out of scope.
- Automatic purging of expired records from the database is out of scope (expiry is enforced at read time).

---

## 4. User Stories

### US-01: Shorten a URL
> As an **API consumer**, I want to submit a long URL and receive a short code so that I can share a concise link.

**Acceptance Criteria:**
- Submitting a valid URL to `POST /shorten` returns HTTP 200 with a JSON body containing `code` (6 alphanumeric characters) and `shortUrl`.
- The short code is unique; no two active records share the same code.
- Submitting the same long URL twice produces two different short codes.

---

### US-02: Follow a Short Link
> As an **end user**, I want to visit a short URL and be automatically redirected to the original long URL so that I reach my destination without knowing the full URL.

**Acceptance Criteria:**
- `GET /{code}` with a valid, non-expired code returns HTTP 302 with a `Location` header pointing to the original URL.
- The redirect happens immediately without intermediate pages.

---

### US-03: Handle an Unknown Code
> As an **end user**, I want to receive a clear error when I follow a short link that was never created so that I understand the link is invalid.

**Acceptance Criteria:**
- `GET /{code}` with a code that does not exist in the database returns HTTP 404 Not Found.

---

### US-04: Handle an Expired Code
> As an **end user**, I want to receive a clear error when I follow a short link that has expired so that I understand the link is no longer valid.

**Acceptance Criteria:**
- `GET /{code}` with a code whose `expires_at` is in the past returns HTTP 410 Gone.
- The response distinguishes expiry (410) from non-existence (404).

---

### US-05: Validate Input URL
> As an **API consumer**, I want the service to reject malformed or missing URLs so that I receive actionable feedback instead of silent failures.

**Acceptance Criteria:**
- Submitting an empty body, missing `url` field, or a syntactically invalid URL returns HTTP 400 Bad Request with a descriptive error message.

---

## 5. Functional Requirements

### 5.1 URL Shortening — `POST /shorten`

| ID | Requirement |
|----|-------------|
| FR-01 | The endpoint SHALL accept `Content-Type: application/json` with body `{ "url": "<string>" }`. |
| FR-02 | The endpoint SHALL validate that `url` is present and is a syntactically valid URL (scheme + host at minimum). |
| FR-03 | On invalid or missing `url`, the endpoint SHALL return HTTP 400 Bad Request with a JSON error body. |
| FR-04 | The service SHALL generate a short code of exactly 6 characters using the alphanumeric character set `[A-Za-z0-9]` (62 possible characters, ~56.8 billion combinations). |
| FR-05 | Short code generation SHALL be random. Sequential or predictable codes are not acceptable. |
| FR-06 | The service SHALL verify uniqueness of a newly generated code against existing records before persisting. On collision, a new code SHALL be regenerated (retry loop). |
| FR-07 | Each submission of the same long URL SHALL produce a distinct short code (no deduplication). |
| FR-08 | The service SHALL persist the mapping with: `id` (auto-generated PK), `code` (unique), `original_url`, `created_at` (UTC timestamp at insert time), `expires_at` (`created_at + 30 days`). |
| FR-09 | On success, the endpoint SHALL return HTTP 200 with JSON body: `{ "code": "<6-char code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-10 | The `shortUrl` value SHALL be constructed from the server's own base URL (host + port), not a hardcoded string. |

### 5.2 Redirect — `GET /{code}`

| ID | Requirement |
|----|-------------|
| FR-11 | The endpoint SHALL look up the provided `{code}` in the database (case-sensitive match). |
| FR-12 | If no record exists for the code, the endpoint SHALL return HTTP 404 Not Found with a JSON error body. |
| FR-13 | If a record exists and `expires_at` is in the past (compared to the current UTC time), the endpoint SHALL return HTTP 410 Gone with a JSON error body. |
| FR-14 | If a record exists and is not expired, the endpoint SHALL return HTTP 302 Found with a `Location` header set to `original_url`. |
| FR-15 | The redirect response body MAY be empty or contain a minimal HTML redirect fallback. |

### 5.3 Database Schema

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

| ID | Requirement |
|----|-------------|
| FR-16 | The `code` column SHALL have a UNIQUE constraint enforced at the database level. |
| FR-17 | The `expires_at` column SHALL be indexed or the `code` column lookup SHALL be efficient (O(1) with index). |
| FR-18 | All timestamps SHALL be stored in UTC. |

### 5.4 Error Response Format

All error responses SHALL return JSON in the following format:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Short code 'abc123' does not exist."
}
```

| ID | Requirement |
|----|-------------|
| FR-19 | Error responses SHALL include `status` (HTTP status code), `error` (short description), and `message` (human-readable detail). |
| FR-20 | Error responses SHALL set `Content-Type: application/json`. |

### 5.5 Unit Tests

| ID | Requirement |
|----|-------------|
| FR-21 | Unit tests SHALL cover the short code generation logic: correct length (6 chars), correct character set (`[A-Za-z0-9]`), and statistical uniqueness across a large sample. |
| FR-22 | Unit tests SHALL cover the expiry-check logic: a record with `expires_at` in the past is considered expired; a record with `expires_at` in the future is considered valid; a record with `expires_at` exactly equal to now is considered expired (boundary condition). |
| FR-23 | Unit tests SHALL mock external dependencies (database) to remain fast and isolated. |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-01 | `POST /shorten` SHALL complete within **200 ms** at the 95th percentile under normal load (single-instance deployment, unloaded DB). |
| NFR-02 | `GET /{code}` SHALL complete within **50 ms** at the 95th percentile (single DB index lookup). |
| NFR-03 | The service SHALL handle at least **100 concurrent requests** without errors on a single instance with standard JVM heap settings. |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-04 | The service SHALL validate and sanitize the submitted URL to prevent storage of obviously malicious schemes (e.g., `javascript:`, `data:`). Only `http://` and `https://` schemes SHALL be accepted. |
| NFR-05 | The service SHALL not follow or pre-fetch the submitted URL (no SSRF risk introduced by the shortener itself). |
| NFR-06 | Database queries SHALL use parameterized statements (via JPA/JDBC) to prevent SQL injection. |
| NFR-07 | The `code` path variable SHALL be validated to match the pattern `[A-Za-z0-9]{6}` before any database lookup to prevent unexpected input from reaching the database. |
| NFR-08 | The service SHALL not expose internal stack traces in API error responses in production. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-09 | The service SHALL return appropriate HTTP error codes (not 500) for all anticipated error conditions (invalid input, not found, expired). |
| NFR-10 | Short code uniqueness collisions SHALL be handled gracefully with automatic retry (up to 5 attempts) before returning a 500 error. |
| NFR-11 | The service SHALL start up cleanly if the database schema already exists (idempotent schema migration via Flyway or Liquibase, or Spring Boot DDL auto). |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-12 | The service SHALL be stateless (no in-memory session state) so that multiple instances can run behind a load balancer without sticky sessions. |
| NFR-13 | The database UNIQUE constraint on `code` SHALL be the authoritative concurrency guard, ensuring no duplicate codes even under concurrent inserts across multiple instances. |

---

## 7. Technical Constraints

| Constraint | Detail |
|------------|--------|
| **Framework** | Spring Boot (latest stable 3.x). REST layer via Spring MVC (`@RestController`). |
| **Database** | PostgreSQL. JPA/Hibernate or Spring Data JPA for ORM. |
| **Build Tool** | Maven or Gradle (project preference; no existing `pom.xml`/`build.gradle` found — team to decide). |
| **Java Version** | Java 17 or 21 (LTS), compatible with Spring Boot 3.x. |
| **Schema Migration** | Flyway or Liquibase recommended for repeatable, version-controlled schema changes. |
| **Testing** | JUnit 5 + Mockito for unit tests. Spring Boot Test for integration tests (optional). |
| **No existing code** | This is a greenfield project; no legacy patterns or APIs to maintain backward compatibility with. |
| **Short URL host** | The host portion of `shortUrl` depends on deployment configuration. Must be externalized via `application.properties` (e.g., `app.base-url=http://localhost:8080`) rather than hardcoded. |
| **Expiry granularity** | Expiry is enforced at read time only. No background job is required to delete or disable expired rows. |

---

## 8. Success Metrics

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| All acceptance criteria pass | 100% | Automated test suite (unit + integration) runs green on CI |
| Short code uniqueness | 0 duplicate codes in production | Database UNIQUE constraint violations = 0 |
| Correct expiry behavior | 100% of expired codes return 410; 0% return 302 | Integration tests with time-mocked clock |
| API error rate (5xx) | < 0.1% of requests | Application metrics / logs |
| `POST /shorten` p95 latency | < 200 ms | Load test with 100 concurrent users |
| `GET /{code}` p95 latency | < 50 ms | Load test with 100 concurrent users |
| Unit test coverage (shortening + expiry logic) | >= 90% line coverage on service classes | JaCoCo report |
| Invalid URL rejection rate | 100% of `javascript:` / `data:` / missing-scheme URLs return 400 | Automated test cases |

---

## 9. Implementation Risks

### Risk 1: Short Code Collision Under High Concurrency

**Likelihood:** Low-Medium (increases with volume; 6-char alphanumeric = ~56.8B combinations, but collisions grow with scale)
**Impact:** Medium — two concurrent inserts with the same generated code would cause one to fail without a retry.
**Mitigation:**
- Implement a retry loop (up to 5 attempts) in the service layer before surfacing a 500 error.
- Rely on the database UNIQUE constraint as the final arbiter (optimistic insert, catch `DataIntegrityViolationException`, retry).
- For high-volume scenarios in the future, consider pre-generating a pool of codes.

---

### Risk 2: SSRF / Malicious URL Storage

**Likelihood:** Medium — open APIs without auth are frequently abused.
**Impact:** High — storing and redirecting to internal-network URLs (e.g., `http://169.254.169.254/`) could expose infrastructure.
**Mitigation:**
- Whitelist accepted URL schemes to `http` and `https` only.
- Optionally validate that the URL host is not a private/loopback IP range (RFC 1918) in a future iteration.
- Do not pre-fetch or follow the URL during shortening.

---

### Risk 3: Clock Skew in Multi-Instance Deployments

**Likelihood:** Low
**Impact:** Medium — if two instances have slightly different system clocks, expiry checks near the boundary may behave inconsistently.
**Mitigation:**
- Store and compare all timestamps in UTC.
- Use the database server's clock (`NOW()`) for `created_at`/`expires_at` to ensure a single time source.
- Alternatively, inject a `Clock` bean in the service layer to allow deterministic testing and easy replacement.

---

### Risk 4: Database Unavailability During Startup or Runtime

**Likelihood:** Low
**Impact:** High — all endpoints depend on the database.
**Mitigation:**
- Use Spring Boot's health actuator (`/actuator/health`) to expose readiness status.
- Configure connection pool (HikariCP) with appropriate timeout and retry settings.
- Return 503 Service Unavailable (via global exception handler) if the DB is unreachable, rather than letting the JVM crash.

---

### Risk 5: `shortUrl` Host Misconfiguration

**Likelihood:** Medium — easy to overlook in deployment.
**Impact:** Low-Medium — generated short URLs would point to the wrong host (e.g., `localhost` in production).
**Mitigation:**
- Externalize `app.base-url` as a required configuration property with no default.
- Fail fast at startup if the property is not set (use `@Value` with no default or a `@ConfigurationProperties` validator).

---

*End of Document*
