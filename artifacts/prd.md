# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git
**Branch:** main

---

## 1. Executive Summary

The URL Shortener Service is a Spring Boot REST API that converts arbitrarily long URLs into compact 6-character alphanumeric codes. Users submit a long URL and receive a shortened alias; visiting that alias redirects the browser to the original destination. Short URLs expire automatically after 30 days. The service exposes two endpoints — one to create a short code and one to resolve and redirect — backed by a PostgreSQL database. The goal is a lightweight, self-contained microservice that can be deployed independently and integrated into any existing platform.

---

## 2. Problem Statement

Long URLs are unwieldy in practice: they break in emails, exceed character limits in social posts, and are difficult to communicate verbally. Today there is no internal URL shortening capability in this platform, forcing users to rely on third-party services (Bitly, TinyURL, etc.) that introduce external dependencies, expose internal link structures to third parties, and provide no control over expiration policies or analytics.

**Impact:**
- Users sharing deep-link URLs in communications face truncation and broken links.
- Reliance on external shorteners creates availability risk (service outages, domain changes).
- No audit trail or expiration control over shortened links shared internally.
- Security and compliance teams cannot govern where shortened links point.

Building an internal URL shortener solves these problems by keeping link management in-house, enforcing expiration, and providing a foundation for future analytics.

---

## 3. Goals and Non-Goals

### Goals

- Provide a `POST /shorten` endpoint that accepts a long URL and returns a unique 6-character alphanumeric short code plus a fully qualified short URL.
- Provide a `GET /{code}` endpoint that resolves a short code and issues an HTTP 302 redirect to the original URL.
- Automatically expire short URLs 30 days after creation; return HTTP 410 Gone for expired codes.
- Return HTTP 404 Not Found for codes that do not exist in the system.
- Store all URL mappings in PostgreSQL with fields: `id`, `code`, `original_url`, `created_at`, `expires_at`.
- Guarantee uniqueness of short codes; duplicate long URLs receive a new, distinct code each time.
- Provide unit test coverage for short code generation logic and expiry checking logic.

### Non-Goals

- Custom/vanity short codes chosen by the user.
- Click tracking, analytics, or reporting dashboards.
- User authentication, authorization, or per-user link management.
- Rate limiting or abuse prevention (out of scope for v1).
- Link editing or deletion endpoints.
- QR code generation.
- URL validation beyond basic format checking.
- High-availability clustering or multi-region deployment.
- Admin UI or management console.
- Configurable expiration windows (always 30 days in v1).

---

## 4. User Stories

| # | User Story |
|---|-----------|
| US-1 | As an **API consumer**, I want to submit a long URL and receive a short code so that I can share compact links in communications. |
| US-2 | As an **API consumer**, I want to receive the full short URL (including host) in the response so that I can use it immediately without constructing it myself. |
| US-3 | As an **end user**, I want visiting a short URL to redirect me seamlessly to the original destination so that I reach the intended page without extra steps. |
| US-4 | As an **end user**, I want to see a 404 response when I visit a short code that does not exist so that I get clear feedback that the link is invalid. |
| US-5 | As an **end user**, I want to see a 410 Gone response when I visit an expired short URL so that I understand the link has intentionally expired rather than never existing. |
| US-6 | As an **API consumer**, I want each submission of the same long URL to produce a distinct short code so that I can independently manage and distribute multiple aliases for the same destination. |
| US-7 | As a **developer**, I want the short code generation and expiry logic covered by unit tests so that I can confidently refactor or extend the service without introducing regressions. |

---

## 5. Functional Requirements

### 5.1 Short Code Generation

| ID | Requirement |
|----|-------------|
| FR-1 | The system MUST generate short codes that are exactly 6 characters in length. |
| FR-2 | Short codes MUST consist only of alphanumeric characters (A–Z, a–z, 0–9), giving a character space of 62 and a total of 62^6 ≈ 56.8 billion possible codes. |
| FR-3 | Short codes MUST be randomly generated; they MUST NOT be sequential or predictable. |
| FR-4 | The system MUST verify uniqueness of a generated code against the database before persisting. On collision, the system MUST regenerate until a unique code is found. |
| FR-5 | Each call to `POST /shorten` MUST produce a new, independent short code even if the submitted URL is identical to a previously shortened URL. |

### 5.2 POST /shorten Endpoint

| ID | Requirement |
|----|-------------|
| FR-6 | The endpoint MUST accept `Content-Type: application/json` with a body of `{ "url": "<string>" }`. |
| FR-7 | On success, the endpoint MUST return HTTP 201 Created with a JSON body: `{ "code": "<6-char code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-8 | The `shortUrl` field MUST be a fully qualified URL constructed from the server's host and the generated code. |
| FR-9 | The system MUST persist a record to the `url_mappings` table with `code`, `original_url`, `created_at` (current timestamp), and `expires_at` (`created_at` + 30 days). |
| FR-10 | If the `url` field is missing or empty in the request body, the endpoint MUST return HTTP 400 Bad Request with a descriptive error message. |

### 5.3 GET /{code} Endpoint

| ID | Requirement |
|----|-------------|
| FR-11 | When a valid, non-expired code is requested, the endpoint MUST return HTTP 302 Found with a `Location` header set to the original URL. |
| FR-12 | When the requested code does not exist in the database, the endpoint MUST return HTTP 404 Not Found. |
| FR-13 | When the requested code exists in the database but `expires_at` is in the past (relative to the current server time), the endpoint MUST return HTTP 410 Gone. |
| FR-14 | The expiry check MUST be performed at request time; no background cleanup is required for v1. |

### 5.4 Data Model

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGSERIAL / BIGINT | PRIMARY KEY, NOT NULL | Auto-incrementing surrogate key |
| `code` | VARCHAR(6) | UNIQUE, NOT NULL | The short code |
| `original_url` | TEXT | NOT NULL | The original long URL |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL, DEFAULT NOW() | Record creation timestamp |
| `expires_at` | TIMESTAMP WITH TIME ZONE | NOT NULL | Expiration timestamp (created_at + 30 days) |

A unique index on `code` MUST be created to enforce uniqueness at the database level and support fast lookups.

### 5.5 Unit Testing

| ID | Requirement |
|----|-------------|
| FR-15 | Unit tests MUST cover the short code generation function, asserting: output length is exactly 6, output contains only alphanumeric characters, and repeated calls produce different codes with high probability. |
| FR-16 | Unit tests MUST cover the expiry check logic, asserting: a record with `expires_at` in the future is treated as valid; a record with `expires_at` in the past is treated as expired. |
| FR-17 | Tests MUST be runnable via `./mvnw test` (or `mvn test`) without external dependencies (use mocks/in-memory state where needed). |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-1 | `GET /{code}` lookups (happy path) MUST complete within 100 ms at the 95th percentile under a load of 100 concurrent requests. |
| NFR-2 | `POST /shorten` requests MUST complete within 200 ms at the 95th percentile under normal load. |
| NFR-3 | The PostgreSQL `code` column MUST be indexed to ensure O(log n) lookup performance. |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-4 | The service MUST NOT execute or follow the submitted URL; it only stores the string as-is. |
| NFR-5 | Database queries MUST use parameterized statements (Spring Data JPA / PreparedStatement) to prevent SQL injection. |
| NFR-6 | The service MUST NOT include sensitive configuration (DB credentials, secrets) in source code; all secrets MUST be externalized via environment variables or a secrets manager. |
| NFR-7 | Input URLs MUST be validated for basic format correctness (e.g., non-empty, starts with http:// or https://) before storage. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-8 | The service MUST return well-formed JSON error responses (including an `error` or `message` field) for all 4xx and 5xx responses. |
| NFR-9 | Database connection failures MUST result in HTTP 503 Service Unavailable, not unhandled exceptions. |
| NFR-10 | The application MUST start cleanly and be ready to serve traffic within 30 seconds of launch. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-11 | The service MUST be stateless (no in-process session state) so that multiple instances can run behind a load balancer sharing the same PostgreSQL database. |
| NFR-12 | The code generation approach MUST remain practical up to at least 10 million stored codes (collision probability stays below 0.02% at that volume given the 56.8 billion key space). |

---

## 7. Technical Constraints

### 7.1 Stack and Framework

- **Language:** Java (version compatible with chosen Spring Boot release; recommend Java 17 LTS).
- **Framework:** Spring Boot (REST API via Spring Web MVC).
- **Database:** PostgreSQL — the sole supported data store; no in-memory H2 in production.
- **ORM/Data Access:** Spring Data JPA (Hibernate) or Spring JDBC Template.
- **Build Tool:** Maven (standard Spring Initializr layout with `pom.xml`).
- **Testing:** JUnit 5 + Mockito (standard Spring Boot test slice support).

### 7.2 Repository Layout (Expected)

```
url-shortener/
  src/
    main/
      java/com/example/urlshortener/
        controller/     # REST controllers
        service/        # Business logic (code generation, expiry)
        repository/     # Spring Data JPA repositories
        model/          # JPA entity
        dto/            # Request/response DTOs
      resources/
        application.properties (or .yml)
        db/migration/   # Schema scripts (Flyway or Liquibase recommended)
    test/
      java/com/example/urlshortener/
        service/        # Unit tests for service layer
  pom.xml
  README.md
```

### 7.3 API Contract

**POST /shorten**

```
Request:
  POST /shorten HTTP/1.1
  Content-Type: application/json
  { "url": "https://www.example.com/very/long/path?query=param" }

Response 201 Created:
  Content-Type: application/json
  { "code": "aB3xY9", "shortUrl": "http://localhost:8080/aB3xY9" }

Response 400 Bad Request:
  { "error": "url must not be blank" }
```

**GET /{code}**

```
Request:
  GET /aB3xY9 HTTP/1.1

Response 302 Found:
  Location: https://www.example.com/very/long/path?query=param

Response 404 Not Found:
  { "error": "Short code not found" }

Response 410 Gone:
  { "error": "Short URL has expired" }
```

### 7.4 Database Configuration

- Connection string, username, and password MUST be supplied via environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) or Spring `application.properties` overrides.
- Schema creation: a DDL migration script (SQL file or Flyway migration) MUST be provided so the schema can be reproduced in any environment.
- PostgreSQL version: 13 or later.

---

## 8. Success Metrics

| Metric | Target |
|--------|--------|
| Short code uniqueness rate | 100% — zero duplicate codes in production |
| Redirect accuracy | 100% — `GET /{code}` always resolves to the correct original URL |
| Expiry enforcement | 100% — no valid redirect served after `expires_at` has passed |
| Unit test coverage (service layer) | >= 90% line coverage on `CodeGeneratorService` and expiry logic |
| All unit tests passing | 100% green on `mvn test` |
| `POST /shorten` p95 latency | < 200 ms under 100 concurrent users |
| `GET /{code}` p95 latency | < 100 ms under 100 concurrent users |
| HTTP error response conformance | 100% of error responses include a JSON body with an `error` field |
| Zero security findings | No SQL injection, no secrets in source, parameterized queries throughout |

---

## 9. Implementation Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|------------|--------|------------|
| R-1 | **Code collision under high write volume.** Randomly generated 6-char codes have a finite key space; at scale, collision retries increase latency. | Low (56.8B keys, v1 volume modest) | Medium | Implement collision retry with a configurable max-attempts limit (e.g., 5). If all retries fail, return 503. Log collision events for monitoring. |
| R-2 | **Clock skew causing incorrect expiry decisions.** If the server clock drifts, borderline-expired codes may be served or valid codes rejected. | Low | Low | Use `TIMESTAMP WITH TIME ZONE` in PostgreSQL and compare against `NOW()` in the DB query or use a reliable server-side `Instant.now()` with UTC. |
| R-3 | **Database as single point of failure.** A PostgreSQL outage takes down the entire service. | Medium | High | Implement graceful degradation: catch `DataAccessException`, return 503 with a meaningful message. For v2, consider read replicas or connection pooling (HikariCP). |
| R-4 | **Schema drift between environments.** Manual SQL applied inconsistently across dev/staging/prod. | Medium | Medium | Enforce schema migrations via Flyway or Liquibase from day one. Never apply schema changes manually. |
| R-5 | **URL injection / malicious redirects.** Users could submit URLs pointing to phishing or malware sites. | Medium | High | For v1, validate that the URL starts with `http://` or `https://` and is syntactically valid. For v2, consider a URL reputation check. Document that content moderation is out of scope for v1. |
| R-6 | **Test coverage gaps in edge cases.** Expiry logic may not be tested for boundary conditions (exactly at expiry moment). | Medium | Medium | Write explicit tests for: `expires_at` = now (boundary), `expires_at` = now - 1ms (expired), `expires_at` = now + 1ms (valid). Use a clock abstraction (`Clock`) injected into the service to enable deterministic testing. |
| R-7 | **Short URL host resolution in production.** The `shortUrl` in the response is constructed from `HttpServletRequest` or a configured base URL; if misconfigured, returned URLs will be unreachable. | Low | Medium | Externalize the base URL as a configuration property (`app.base-url`), defaulting to the request host in development. Document required configuration for deployment. |

---

*End of PRD v1.0*
