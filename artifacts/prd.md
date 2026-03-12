# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git
**Branch:** main

---

## 1. Executive Summary

This document describes the requirements for a URL Shortener Service — a Spring Boot REST API that converts long URLs into compact 6-character alphanumeric codes. Users submit a long URL via a POST endpoint and receive a short code. Visiting the short code path triggers an HTTP 302 redirect to the original destination. Short URLs expire after 30 days; expired codes return HTTP 410 Gone. Mappings are persisted in PostgreSQL. The service is stateless, self-contained, and designed for straightforward deployment.

---

## 2. Problem Statement

Long URLs are cumbersome to share, embed in communications, and track. They break across line wraps, resist easy memorization, and expose internal path structures. There is no existing short-link infrastructure in this project.

**Impact:**
- Users sharing links manually must copy error-prone, lengthy strings.
- There is no built-in mechanism for link lifecycle management (expiry, deactivation).
- Without short codes, analytics and link attribution are difficult to add in future iterations.

This service provides the foundational infrastructure to address these issues with a minimal, well-defined API surface.

---

## 3. Goals and Non-Goals

### Goals

- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric short code.
- Provide a `GET /{code}` endpoint that redirects the caller to the original URL via HTTP 302.
- Enforce a 30-day expiry on all short codes; return HTTP 410 Gone for expired codes.
- Return HTTP 404 Not Found for codes that have never existed.
- Guarantee short code uniqueness at the time of creation.
- Issue a new, distinct code every time the same long URL is submitted (no deduplication).
- Persist all URL mappings in PostgreSQL with columns: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Provide unit tests covering the code-generation logic and expiry check logic.

### Non-Goals

- Custom or user-defined short codes (vanity URLs) — not in scope.
- Link analytics, click tracking, or reporting dashboards — not in scope.
- Authentication or user account management — not in scope.
- Bulk URL shortening — not in scope.
- Manual deactivation or deletion of short URLs — not in scope.
- Rate limiting or abuse prevention — not in scope for this iteration.
- URL preview or safe-browsing checks — not in scope.
- QR code generation — not in scope.
- Admin interface or management console — not in scope.

---

## 4. User Stories

**US-01 — Shorten a URL**
As an API consumer, I want to submit a long URL and receive a short code so that I can share a concise link.

**US-02 — Redirect via short code**
As an end user, I want to visit a short URL and be transparently redirected to the original destination so that I reach the intended page without knowing the full URL.

**US-03 — Handle unknown codes gracefully**
As an API consumer, I want to receive a clear 404 response when I request a short code that has never existed so that I can distinguish a bad link from an expired one.

**US-04 — Handle expired codes gracefully**
As an API consumer, I want to receive a clear 410 Gone response when I request a short code that has expired so that I understand the link is no longer valid.

**US-05 — Receive a new code for repeated submissions**
As an API consumer, I want each submission of the same long URL to produce a fresh, independent short code so that I can manage distinct link instances separately.

**US-06 — Rely on code uniqueness**
As an API consumer, I want every generated short code to be unique so that a single code never resolves to more than one destination.

---

## 5. Functional Requirements

### 5.1 POST /shorten — Create a Short URL

| ID | Requirement |
|----|-------------|
| FR-01 | The endpoint MUST accept `Content-Type: application/json` with a body of `{ "url": "<string>" }`. |
| FR-02 | The `url` field MUST be a non-null, non-empty string. If absent or blank, the service MUST return HTTP 400 Bad Request with a descriptive error message. |
| FR-03 | The service SHOULD validate that the submitted value is a well-formed URL (scheme `http` or `https`, valid host). Invalid URLs MUST return HTTP 400. |
| FR-04 | On success, the service MUST generate a new, unique 6-character alphanumeric code (`[A-Za-z0-9]`). |
| FR-05 | The same long URL submitted multiple times MUST produce a different code each time (no deduplication). |
| FR-06 | The service MUST persist a record with: `id` (auto-generated primary key), `code`, `original_url`, `created_at` (timestamp of creation, UTC), `expires_at` (`created_at` + 30 days). |
| FR-07 | On success, the service MUST return HTTP 201 Created with body `{ "code": "<6-char-code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-08 | In the event of a code collision during generation, the service MUST retry generation until a unique code is obtained (collision probability is negligible but must be handled). |

### 5.2 GET /{code} — Redirect to Original URL

| ID | Requirement |
|----|-------------|
| FR-09 | The `{code}` path variable MUST be treated as case-sensitive. |
| FR-10 | If the code does not exist in the database, the service MUST return HTTP 404 Not Found. |
| FR-11 | If the code exists but `expires_at` is in the past (relative to the server's current UTC time), the service MUST return HTTP 410 Gone. |
| FR-12 | If the code exists and has not expired, the service MUST return HTTP 302 Found with a `Location` header set to the `original_url`. |
| FR-13 | Expiry evaluation MUST use server-side UTC time; it MUST NOT rely on client-supplied timestamps. |

### 5.3 Short Code Generation

| ID | Requirement |
|----|-------------|
| FR-14 | Codes MUST be exactly 6 characters long. |
| FR-15 | Codes MUST consist only of alphanumeric characters (`[A-Za-z0-9]`), yielding a character space of 62 possible characters per position (62^6 = ~56.8 billion combinations). |
| FR-16 | Codes MUST be randomly generated using a cryptographically adequate random source (e.g., `SecureRandom`). |
| FR-17 | Code uniqueness MUST be enforced at the database level via a `UNIQUE` constraint on the `code` column, in addition to application-level checks. |

### 5.4 Database Schema

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

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-01 | `POST /shorten` MUST respond in under 300 ms at p95 under normal load (single-instance deployment, PostgreSQL on localhost or equivalent). |
| NFR-02 | `GET /{code}` MUST respond in under 100 ms at p95 under normal load. |
| NFR-03 | The database index on `code` MUST ensure O(log n) lookup for redirect resolution. |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-04 | Short codes MUST be generated using `java.security.SecureRandom` to prevent predictability. |
| NFR-05 | The `original_url` stored and returned in the `Location` header MUST be the exact URL as submitted, with no server-side modification, to prevent open-redirect manipulation beyond what the caller provides. |
| NFR-06 | SQL queries MUST use parameterized statements (JPA/Hibernate or Spring JDBC Template with bind parameters) — no string concatenation in queries. |
| NFR-07 | Input length for `url` MUST be capped (e.g., 2048 characters) to prevent denial-of-service via large payloads. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-08 | The service MUST handle PostgreSQL connection failures gracefully and return HTTP 503 Service Unavailable rather than an unhandled exception. |
| NFR-09 | The application MUST start successfully with a healthy PostgreSQL connection; Spring Boot Actuator health checks SHOULD be enabled. |
| NFR-10 | Database schema MUST be managed via a migration tool (Flyway or Liquibase) to ensure repeatable, versioned schema setup. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-11 | The service MUST be stateless — no in-memory state between requests — so that multiple instances can run behind a load balancer without coordination. |
| NFR-12 | The `code` column index MUST be present from initial deployment to support lookup performance as data grows. |

### 6.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-13 | The application MUST emit structured logs (at minimum INFO level) for every shorten request and every redirect, including the code and outcome. |
| NFR-14 | Spring Boot Actuator SHOULD expose `/actuator/health` for liveness and readiness checks. |

---

## 7. Technical Constraints

| ID | Constraint |
|----|------------|
| TC-01 | **Framework:** Spring Boot (latest stable 3.x). REST layer via Spring MVC (`@RestController`). |
| TC-02 | **Language:** Java 17 or higher (aligned with Spring Boot 3.x baseline). |
| TC-03 | **Database:** PostgreSQL. ORM via Spring Data JPA / Hibernate or Spring JDBC Template. |
| TC-04 | **Build tool:** Maven or Gradle (project standard; Maven assumed given Spring Initializr defaults). |
| TC-05 | **Schema migrations:** Flyway or Liquibase MUST be used; no manual DDL execution in production. |
| TC-06 | **Testing:** JUnit 5 with Spring Boot Test. Mockito for unit-level mocks. H2 in-memory or Testcontainers for integration tests. |
| TC-07 | **Configuration:** Database credentials and host MUST be supplied via environment variables or `application.properties`/`application.yml`; no hardcoded secrets. |
| TC-08 | **Repository:** The project is hosted at `https://github.com/ashish-chulpar-polestar/url-shortener.git`, branch `main`. |
| TC-09 | **No external short-code libraries:** The code generation algorithm MUST be implemented directly in the service (no third-party shortening SDKs). |

---

## 8. Success Metrics

| ID | Metric | Target |
|----|--------|--------|
| SM-01 | All acceptance criteria pass in CI | 100% |
| SM-02 | Unit test coverage for code-generation and expiry logic | >= 90% line coverage |
| SM-03 | `POST /shorten` p95 latency (local integration environment) | < 300 ms |
| SM-04 | `GET /{code}` p95 latency (local integration environment) | < 100 ms |
| SM-05 | Zero known SQL injection or open-redirect vulnerabilities in code review | 0 critical findings |
| SM-06 | Schema migrations run cleanly on a fresh PostgreSQL instance | 100% success |
| SM-07 | Expired-code and unknown-code paths return correct HTTP status codes in integration tests | 100% |

---

## 9. Implementation Risks

### Risk 1 — Short Code Collision Under High Concurrency
**Likelihood:** Low (62^6 space is large)
**Impact:** Medium — two concurrent requests could generate the same code, causing one to fail at the DB unique constraint.
**Mitigation:** Catch the unique-constraint violation at the application layer and retry code generation up to N times (e.g., 5 retries) before returning HTTP 500. Document the retry logic with a unit test.

### Risk 2 — Expired Records Accumulate Without Cleanup
**Likelihood:** High (no TTL mechanism in PostgreSQL by default)
**Impact:** Low initially, but table growth degrades query performance over time.
**Mitigation:** Add a note in the codebase for a future scheduled job (e.g., Spring `@Scheduled`) to purge records where `expires_at < NOW()`. The index on `expires_at` should be created alongside the `code` index to support efficient purge queries. This cleanup task is out of scope for v1 but must be planned for.

### Risk 3 — Malicious or Unvalidated URLs in Redirect
**Likelihood:** Medium
**Impact:** Medium — service could be used to redirect users to phishing or malware pages.
**Mitigation:** Enforce URL scheme validation (`http`/`https` only) at ingestion time. Document that safe-browsing integration is a future non-goal. Log all shortened URLs for audit purposes.

### Risk 4 — Database Unavailability at Startup
**Likelihood:** Low in production; Medium in local/CI dev environments
**Impact:** High — service is entirely dependent on PostgreSQL.
**Mitigation:** Use Spring Boot's health check endpoint; configure container orchestration (Docker Compose, Kubernetes) with a `depends_on` health check for the database service. Document local setup prerequisites clearly in README.

### Risk 5 — `shortUrl` Host Construction Incorrect in Proxied Environments
**Likelihood:** Medium when deployed behind a reverse proxy or load balancer
**Impact:** Medium — the returned `shortUrl` could contain an internal hostname instead of the public-facing domain.
**Mitigation:** Use Spring's `ServletUriComponentsBuilder` (or equivalent) to derive the base URL from the incoming request's `Host` header, respecting `X-Forwarded-*` headers when `server.forward-headers-strategy=native` is configured.

---

## Appendix A: API Contract Summary

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=true"
}
```

**Response — 201 Created**
```json
{
  "code": "aB3xZ9",
  "shortUrl": "http://localhost:8080/aB3xZ9"
}
```

**Response — 400 Bad Request** (missing or invalid URL)
```json
{
  "error": "Invalid or missing URL"
}
```

---

### GET /{code}

**Response — 302 Found** (valid, non-expired code)
```
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path?with=query&params=true
```

**Response — 404 Not Found** (code never existed)
```json
{
  "error": "Short URL not found"
}
```

**Response — 410 Gone** (code exists but has expired)
```json
{
  "error": "Short URL has expired"
}
```

---

## Appendix B: Acceptance Criteria Checklist

- [ ] Short codes are exactly 6 alphanumeric characters.
- [ ] A unique constraint on `code` exists in the database schema.
- [ ] Submitting the same long URL twice produces two distinct short codes.
- [ ] `GET /{code}` returns HTTP 302 with correct `Location` for a valid, unexpired code.
- [ ] `GET /{code}` returns HTTP 404 for a code that does not exist in the database.
- [ ] `GET /{code}` returns HTTP 410 for a code whose `expires_at` timestamp is in the past.
- [ ] Unit tests exist for the code-generation logic (randomness, length, character set).
- [ ] Unit tests exist for the expiry-check logic (before expiry, at expiry boundary, after expiry).
- [ ] Database schema is applied via a migration script (Flyway or Liquibase), not manual DDL.
- [ ] Application configuration (DB host, credentials) is externalized and not hardcoded.
