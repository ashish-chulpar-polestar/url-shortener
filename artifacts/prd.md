# Product Requirements Document: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Draft
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git
**Branch:** main

---

## 1. Executive Summary

The URL Shortener Service is a lightweight Spring Boot REST API that converts arbitrarily long URLs into compact 6-character alphanumeric codes. Users submit a long URL via a POST endpoint and receive a short URL in return. Accessing the short URL performs an HTTP 302 redirect to the original destination. Each short URL has a 30-day TTL, after which it returns HTTP 410 Gone. Mappings are persisted in PostgreSQL. The service is self-contained, stateless in application logic, and designed to be deployed as a single Spring Boot application backed by a managed database.

---

## 2. Problem Statement

Long URLs are unwieldy in communications, social media posts, print materials, and QR codes. They:

- Exceed character limits on platforms like SMS and Twitter.
- Are difficult to type manually and error-prone.
- Expose internal path structures and query parameters, leaking implementation details.
- Degrade readability in documents and emails.

There is no existing internal URL shortening capability. Teams and users currently rely on third-party services (Bitly, TinyURL), which introduces data privacy concerns, external dependencies, and lack of control over link expiry policies. Building an in-house service resolves these issues while providing a controlled, auditable, and policy-compliant solution.

---

## 3. Goals and Non-Goals

### Goals

- Provide a REST API endpoint to shorten any valid URL into a unique 6-character alphanumeric code.
- Redirect short codes to their original URLs via HTTP 302.
- Enforce a 30-day expiry on all short URLs.
- Return semantically correct HTTP status codes (302 Found, 404 Not Found, 410 Gone).
- Persist all URL mappings durably in PostgreSQL.
- Generate unique short codes that do not collide with existing active or expired codes.
- Issue a new short code for each submission, even for duplicate long URLs.
- Provide unit test coverage for core shortening logic and expiry checking.

### Non-Goals

- **Custom alias support:** Users cannot choose their own short code.
- **Analytics and click tracking:** No tracking of redirect counts, geolocation, referrer, or timestamps of access.
- **User authentication or accounts:** No login, API keys, or per-user URL management in this version.
- **URL editing or deletion:** No endpoint to update or deactivate a short URL before its natural expiry.
- **Bulk shortening:** No batch endpoint for shortening multiple URLs in a single request.
- **QR code generation:** Not in scope.
- **Admin dashboard or UI:** API-only; no frontend.
- **Rate limiting:** Not addressed in this version.
- **Cache layer:** No Redis or in-memory caching in this version.
- **Vanity domains:** The short URL host is fixed to the service's deployment host.

---

## 4. User Stories

**US-01 — Shorten a URL**
As an API consumer, I want to POST a long URL and receive a short code so that I can share a compact link instead of the full URL.

**US-02 — Redirect via short code**
As an end user, I want to visit a short URL and be transparently redirected to the original destination so that I reach the intended page without knowing the full URL.

**US-03 — Handle unknown codes**
As an end user, I want to receive a clear 404 Not Found response when I visit a short URL that was never created so that I know the link is invalid.

**US-04 — Handle expired codes**
As an end user, I want to receive a clear 410 Gone response when I visit a short URL that has passed its 30-day expiry so that I know the link is no longer valid and is not merely missing.

**US-05 — Unique codes per submission**
As an API consumer, I want each submission of the same long URL to produce a distinct new short code so that I can track and distribute links independently.

**US-06 — Predictable short URL format**
As an API consumer, I want the response to include both the raw code and the fully qualified short URL so that I can use either form without constructing the URL myself.

---

## 5. Functional Requirements

### 5.1 URL Shortening — `POST /shorten`

| ID | Requirement |
|----|-------------|
| FR-01 | The endpoint SHALL accept `Content-Type: application/json` with body `{ "url": "<string>" }`. |
| FR-02 | The endpoint SHALL validate that `url` is present and non-empty; return HTTP 400 Bad Request if missing or blank. |
| FR-03 | The endpoint SHALL validate that `url` is a syntactically valid URL (scheme + host minimum); return HTTP 422 Unprocessable Entity if malformed. |
| FR-04 | The endpoint SHALL generate a cryptographically random, 6-character alphanumeric code using characters `[A-Za-z0-9]`. |
| FR-05 | The generated code SHALL be unique across all existing records (active and expired) in the database. Collision retry logic SHALL be implemented. |
| FR-06 | Each call to `POST /shorten` with the same `url` SHALL produce a different code (no de-duplication). |
| FR-07 | The endpoint SHALL persist the mapping with `code`, `original_url`, `created_at` (current UTC timestamp), and `expires_at` (`created_at + 30 days`). |
| FR-08 | On success, the endpoint SHALL return HTTP 201 Created with body `{ "code": "<code>", "shortUrl": "http://<host>/<code>" }`. |
| FR-09 | The `shortUrl` field SHALL use the same scheme and host as the incoming request (derived from `Host` header or a configurable base URL property). |

### 5.2 Redirect — `GET /{code}`

| ID | Requirement |
|----|-------------|
| FR-10 | The endpoint SHALL look up the code in the database. |
| FR-11 | If the code does not exist, the endpoint SHALL return HTTP 404 Not Found with a JSON error body `{ "error": "Not found" }`. |
| FR-12 | If the code exists but `expires_at` is before or equal to the current UTC time, the endpoint SHALL return HTTP 410 Gone with a JSON error body `{ "error": "Link expired" }`. |
| FR-13 | If the code exists and is not expired, the endpoint SHALL return HTTP 302 Found with a `Location` header set to the `original_url`. |
| FR-14 | The redirect response body MAY be empty or contain a minimal HTML redirect; it SHALL NOT expose internal data. |

### 5.3 Data Model — PostgreSQL

| ID | Requirement |
|----|-------------|
| FR-15 | The service SHALL use a table named `short_urls` with columns: `id` (BIGSERIAL PRIMARY KEY), `code` (VARCHAR(6) UNIQUE NOT NULL), `original_url` (TEXT NOT NULL), `created_at` (TIMESTAMPTZ NOT NULL), `expires_at` (TIMESTAMPTZ NOT NULL). |
| FR-16 | The `code` column SHALL have a unique index to enforce uniqueness at the database level. |
| FR-17 | An index SHOULD be created on `code` to support O(log n) lookup performance. |
| FR-18 | Schema initialization SHALL be managed via Spring Boot's supported migration mechanism (Flyway or Liquibase, or `spring.sql.init` scripts). |

### 5.4 Testing

| ID | Requirement |
|----|-------------|
| FR-19 | Unit tests SHALL cover the short code generation function, verifying: output is exactly 6 characters, output only contains `[A-Za-z0-9]`, successive calls produce different codes. |
| FR-20 | Unit tests SHALL cover the expiry check logic, verifying: a record with `expires_at` in the future is treated as active, a record with `expires_at` in the past is treated as expired, a record with `expires_at` exactly equal to now is treated as expired. |
| FR-21 | Integration or slice tests SHOULD cover the `POST /shorten` happy path, `GET /{code}` redirect, 404, and 410 scenarios. |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|----|-------------|
| NFR-01 | `GET /{code}` redirect latency SHALL be under 100 ms (p99) under normal load with a warm database connection pool. |
| NFR-02 | `POST /shorten` latency SHALL be under 300 ms (p99) under normal load. |
| NFR-03 | The service SHALL support at least 200 concurrent requests without degradation under initial deployment sizing. |

### 6.2 Security

| ID | Requirement |
|----|-------------|
| NFR-04 | The `url` input SHALL be stored and returned as-is (no normalisation that could introduce redirect vulnerabilities), but SHALL be validated for well-formedness to prevent injection. |
| NFR-05 | Database access SHALL use parameterized queries or an ORM (Spring Data JPA / JDBC) — no string-concatenated SQL. |
| NFR-06 | The service SHALL NOT follow or pre-fetch the destination URL; it only stores and redirects. |
| NFR-07 | No sensitive internal information (stack traces, SQL errors) SHALL be exposed in HTTP error responses in production profile. |
| NFR-08 | HTTPS SHALL be enforced in production via a reverse proxy or load balancer; the application itself may operate on HTTP internally. |

### 6.3 Reliability and Availability

| ID | Requirement |
|----|-------------|
| NFR-09 | The application SHALL start up successfully and perform a health check (`/actuator/health`) with a live PostgreSQL connection before serving traffic. |
| NFR-10 | Database connection pooling (HikariCP, the Spring Boot default) SHALL be configured with appropriate pool size, connection timeout, and idle timeout. |
| NFR-11 | Transient database errors during `POST /shorten` SHALL result in HTTP 500 with a generic error message; no partial data SHALL be committed. |

### 6.4 Scalability

| ID | Requirement |
|----|-------------|
| NFR-12 | The application tier SHALL be stateless so that multiple instances can run behind a load balancer without session affinity. |
| NFR-13 | The PostgreSQL schema design SHALL not prevent horizontal read scaling via read replicas in the future. |

### 6.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-14 | Spring Boot Actuator SHALL be enabled, exposing at minimum `/actuator/health` and `/actuator/info`. |
| NFR-15 | Application logs SHALL include the short code and outcome (created / redirected / not found / expired) at INFO level for each request. |

---

## 7. Technical Constraints

| Constraint | Detail |
|------------|--------|
| **Framework** | Spring Boot (latest stable 3.x). Must use Spring MVC REST (not WebFlux) unless team opts in explicitly. |
| **Language** | Java 17+ (LTS). |
| **Database** | PostgreSQL. No other database engines are in scope. |
| **ORM / Data Access** | Spring Data JPA or Spring JDBC; raw JDBC is acceptable but not preferred. |
| **Build Tool** | Maven or Gradle (team preference); must produce a runnable fat JAR via `./mvnw package` or `./gradlew bootJar`. |
| **Deployment** | Single Spring Boot application. Containerisation (Docker) is recommended but not strictly required for the initial delivery. |
| **Schema migrations** | Must be reproducible and version-controlled. Flyway or Liquibase preferred; `spring.sql.init.schema-locations` acceptable for initial delivery. |
| **No external cache** | Redis or Memcached are out of scope for this version. |
| **Java randomness** | `SecureRandom` SHALL be used for code generation (not `Math.random()` or `Random`). |
| **Time handling** | All timestamps SHALL be stored and compared in UTC. Use `java.time.Instant` or `java.time.OffsetDateTime`; never `java.util.Date`. |
| **Code uniqueness guarantee** | Application-level retry loop on collision + database unique constraint as final guard. |

---

## 8. Success Metrics

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| Code generation collision rate | < 0.01% under 1M active records | Unit test with seeded randomness; stress test on staging |
| `GET /{code}` p99 latency | < 100 ms | Load test (e.g., k6 or Gatling) against staging |
| `POST /shorten` p99 latency | < 300 ms | Load test against staging |
| HTTP 302 accuracy | 100% of valid, non-expired codes redirect correctly | Integration test suite pass rate |
| HTTP 410 accuracy | 100% of expired codes return 410 (not 404) | Integration test suite pass rate |
| Unit test coverage (core logic) | >= 80% line coverage on service and utility classes | JaCoCo report |
| Successful application startup | Health check returns `UP` within 30 s of container start | CI pipeline health-check step |
| Zero data loss on concurrent writes | No duplicate codes committed under concurrent load | Concurrent integration test with 50 simultaneous POST requests |

---

## 9. Implementation Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| **Short code collision at scale** | Low (6-char alphanumeric = 56.8B combinations) but non-zero at high volume | Medium — duplicate key exception causes failed request | Implement retry loop (max 5 attempts) on `DataIntegrityViolationException`; log and alert if retries exceed threshold |
| **Clock skew between app and DB** | Low | Medium — expiry checks may be off by seconds | Always evaluate expiry using `NOW()` in SQL (`WHERE expires_at > NOW()`) or compare against DB-fetched time, not application clock |
| **Open redirect abuse** | Medium — service redirects to any URL submitted | High — phishing vectors | Consider an optional URL scheme allowlist (`http`, `https` only); log all redirect destinations; add SSRF protection to block private IP ranges |
| **Database connection exhaustion under spike** | Medium | High — service unavailable | Configure HikariCP pool limits appropriately; add `POST /shorten` circuit breaker or queue if needed in future |
| **Unbounded table growth (no purge)** | High over time | Low-Medium — query degradation | Add a scheduled job or DB cron to archive/delete rows where `expires_at < NOW() - interval '7 days'` as a follow-up task |
| **`original_url` storage size** | Low | Low — PostgreSQL TEXT handles up to 1 GB per field | No practical risk; TEXT type is appropriate |
| **Concurrent duplicate code generation** | Low | Low — mitigated by DB unique constraint | Unique constraint on `code` column as final guard; application retries on conflict |
| **Missing input validation leading to XSS/injection** | Medium | High | Validate URL format server-side; return JSON errors (not HTML); use parameterized queries |

---

## Appendix A: API Contract

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/very/long/path?with=query&params=here"
}
```

**Response — 201 Created**
```json
{
  "code": "aB3xZ9",
  "shortUrl": "http://localhost:8080/aB3xZ9"
}
```

**Response — 400 Bad Request** (missing/blank url)
```json
{ "error": "url is required" }
```

**Response — 422 Unprocessable Entity** (malformed url)
```json
{ "error": "url is not a valid URL" }
```

---

### GET /{code}

**Response — 302 Found** (valid, non-expired)
```
HTTP/1.1 302 Found
Location: https://www.example.com/very/long/path?with=query&params=here
```

**Response — 404 Not Found**
```json
{ "error": "Not found" }
```

**Response — 410 Gone**
```json
{ "error": "Link expired" }
```

---

## Appendix B: Database Schema

```sql
CREATE TABLE short_urls (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)  NOT NULL,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_short_urls_code UNIQUE (code)
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
```

---

## Appendix C: Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `app.base-url` | Derived from request `Host` header | Base URL prepended to codes in `shortUrl` response field |
| `app.code-length` | `6` | Length of generated short codes (not configurable at runtime, compile-time constant) |
| `app.expiry-days` | `30` | Number of days until a short URL expires |
| `spring.datasource.url` | — | PostgreSQL JDBC URL (required) |
| `spring.datasource.username` | — | Database username (required) |
| `spring.datasource.password` | — | Database password (required) |
| `spring.datasource.hikari.maximum-pool-size` | `10` | HikariCP max pool size |
