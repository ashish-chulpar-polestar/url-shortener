# Implementation Plan: URL Shortener Service

**Version:** 1.0
**Date:** 2026-03-11
**PRD Reference:** artifacts/prd.md

---

## 1. Architecture Overview

This is a **greenfield Spring Boot 3.x REST API**. The repository contains no existing application code, so every file listed below is a new creation.

```
┌─────────────────────────────────────────────────────────┐
│                     HTTP Client                         │
└──────────────────────┬──────────────────────────────────┘
                       │ POST /shorten  /  GET /{code}
┌──────────────────────▼──────────────────────────────────┐
│               UrlShortenerController                    │
│  (Spring MVC @RestController – request/response layer)  │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                 UrlShortenerService                     │
│  (business logic: code generation, collision retry,     │
│   expiry check, logging)                                │
└──────────┬───────────────────────────┬──────────────────┘
           │                           │
┌──────────▼──────────┐   ┌────────────▼────────────────┐
│  CodeGenerator      │   │  UrlMappingRepository       │
│  (pure utility –    │   │  (Spring Data JPA)          │
│   generates random  │   │                             │
│   6-char codes)     │   └────────────┬────────────────┘
└─────────────────────┘                │
                          ┌────────────▼────────────────┐
                          │       PostgreSQL             │
                          │  table: url_mappings        │
                          └─────────────────────────────┘

Supporting classes
──────────────────
GlobalExceptionHandler  – @ControllerAdvice; maps exceptions → JSON error bodies
ErrorResponse           – JSON error DTO  { error, message }
ShortenRequest          – request DTO     { url }
ShortenResponse         – response DTO    { code, shortUrl }
UrlMapping              – JPA @Entity
```

**Request flow — POST /shorten**
1. Controller receives `ShortenRequest`, delegates to service.
2. Service validates URL format.
3. `CodeGenerator` produces a random 6-char alphanumeric code.
4. Service calls repository to persist `UrlMapping`; on `DataIntegrityViolationException` (duplicate code), retries up to 5 times.
5. Controller returns HTTP 201 with `ShortenResponse`.

**Request flow — GET /{code}**
1. Controller receives code, delegates to service.
2. Service queries repository by code.
3. Not found → throws `CodeNotFoundException` → 404.
4. Found but expired → throws `CodeExpiredException` → 410.
5. Active → controller returns HTTP 302 with `Location` header.

---

## 2. Files to Create

### 2.1 Project Bootstrap

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build descriptor — Spring Boot 3.x parent, dependencies, Maven Wrapper plugin |
| `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper configuration |
| `mvnw` / `mvnw.cmd` | Maven Wrapper shell scripts |
| `src/main/resources/application.properties` | Datasource, JPA, Flyway, base-URL, actuator config |

### 2.2 Application Entry Point

**`src/main/java/com/example/urlshortener/UrlShortenerApplication.java`**
- `@SpringBootApplication` main class.

### 2.3 Domain / Entity

**`src/main/java/com/example/urlshortener/model/UrlMapping.java`**
- JPA `@Entity`, table `url_mappings`.
- Fields: `Long id`, `String code`, `String originalUrl`, `OffsetDateTime createdAt`, `OffsetDateTime expiresAt`.
- `@Column(unique = true)` on `code`.

### 2.4 Repository

**`src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java`**
- `interface UrlMappingRepository extends JpaRepository<UrlMapping, Long>`
- Method: `Optional<UrlMapping> findByCode(String code)`

### 2.5 DTOs

**`src/main/java/com/example/urlshortener/dto/ShortenRequest.java`**
- Fields: `String url`
- Bean Validation: `@NotBlank` on `url`

**`src/main/java/com/example/urlshortener/dto/ShortenResponse.java`**
- Fields: `String code`, `String shortUrl`

**`src/main/java/com/example/urlshortener/dto/ErrorResponse.java`**
- Fields: `String error`, `String message`

### 2.6 Utility

**`src/main/java/com/example/urlshortener/util/CodeGenerator.java`**
- `@Component`
- `String generate()` — uses `SecureRandom` to pick 6 chars from `[A-Za-z0-9]` (62 chars).
- No state; easily unit-testable.

### 2.7 Exceptions

**`src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`**
- Extends `RuntimeException`; holds the code string.

**`src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`**
- Extends `RuntimeException`; holds the code string.

**`src/main/java/com/example/urlshortener/exception/InvalidUrlException.java`**
- Extends `RuntimeException`; holds the offending URL string.

### 2.8 Service

**`src/main/java/com/example/urlshortener/service/UrlShortenerService.java`**
- `@Service`
- `@Value("${app.base-url}")` injected base URL.
- `ShortenResponse shorten(String url)`:
  1. Validate URL via `java.net.URL` (must be `http` or `https` scheme); throw `InvalidUrlException` if invalid.
  2. Loop up to 5 times:
     a. Generate code via `CodeGenerator.generate()`.
     b. Build `UrlMapping` with `createdAt = OffsetDateTime.now(UTC)`, `expiresAt = createdAt + 30 days`.
     c. Try `repository.save(mapping)`.
     d. On `DataIntegrityViolationException`, continue loop; after 5 failures throw `RuntimeException("Could not generate unique code")`.
  3. Return `ShortenResponse(code, baseUrl + "/" + code)`.
- `String resolveCode(String code)`:
  1. Fetch by code; throw `CodeNotFoundException` if absent.
  2. Compare `expiresAt` with `OffsetDateTime.now(UTC)`; throw `CodeExpiredException` if expired.
  3. Return `originalUrl`.
- Log each operation at INFO level (URL redacted to first 50 chars).

### 2.9 Controller

**`src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`**
- `@RestController`
- `POST /shorten` → `@PostMapping("/shorten")`, consumes/produces JSON:
  - `@Valid @RequestBody ShortenRequest` → delegates to service → returns `ResponseEntity.status(201).body(response)`.
- `GET /{code}` → `@GetMapping("/{code}")`:
  - Calls `service.resolveCode(code)` → returns `ResponseEntity.status(302).location(URI.create(url)).build()`.

### 2.10 Exception Handler

**`src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`**
- `@RestControllerAdvice`
- Handlers:
  - `CodeNotFoundException` → 404 with `ErrorResponse("Not Found", "No short URL found for code: {code}")`
  - `CodeExpiredException` → 410 with `ErrorResponse("Gone", "The short URL for code {code} has expired.")`
  - `InvalidUrlException` → 400 with `ErrorResponse("Invalid URL", "The provided URL is not a valid URL.")`
  - `MethodArgumentNotValidException` → 400 with `ErrorResponse("Bad Request", first field error message)`
  - `Exception` (fallback) → 500 with `ErrorResponse("Internal Server Error", "An unexpected error occurred.")` — **no stack trace exposed**.

### 2.11 Database Migration

**`src/main/resources/db/migration/V1__create_url_mappings.sql`**
```sql
CREATE TABLE url_mappings (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(6)   NOT NULL,
    original_url TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_url_mappings_code UNIQUE (code)
);

CREATE INDEX idx_url_mappings_code       ON url_mappings (code);
CREATE INDEX idx_url_mappings_expires_at ON url_mappings (expires_at);
```

### 2.12 Unit Tests

**`src/test/java/com/example/urlshortener/util/CodeGeneratorTest.java`**
- Tests that generated code is exactly 6 characters.
- Tests that generated code matches `[A-Za-z0-9]{6}`.
- Tests that 1000 generated codes are not all identical (randomness sanity check).

**`src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`**
- Mock `CodeGenerator` and `UrlMappingRepository`.
- `shorten()` — success: verifies repository save called, response fields correct.
- `shorten()` — invalid URL: verifies `InvalidUrlException` thrown.
- `shorten()` — collision retry: first `save` throws `DataIntegrityViolationException`, second succeeds; verifies `generate()` called twice.
- `shorten()` — all retries exhausted: all 5 saves throw `DataIntegrityViolationException`; verifies `RuntimeException` thrown.
- `resolveCode()` — active code: returns correct original URL.
- `resolveCode()` — unknown code: `findByCode` returns empty; verifies `CodeNotFoundException`.
- `resolveCode()` — expired code: `expiresAt` in past; verifies `CodeExpiredException`.

**`src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`**
- `@WebMvcTest` with mocked `UrlShortenerService`.
- `POST /shorten` with valid URL → 201, JSON body has `code` and `shortUrl`.
- `POST /shorten` with missing `url` field → 400.
- `POST /shorten` with invalid URL → 400, JSON error body.
- `GET /{code}` active code → 302, `Location` header correct.
- `GET /{code}` unknown code → 404, JSON error body with `"Not Found"`.
- `GET /{code}` expired code → 410, JSON error body with `"Gone"`.

---

## 3. Files to Modify

None — this is a greenfield project with no existing application code.

---

## 4. Database Schema Changes

### New Table

```sql
CREATE TABLE url_mappings (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(6)   NOT NULL,
    original_url TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_url_mappings_code UNIQUE (code)
);

CREATE INDEX idx_url_mappings_code       ON url_mappings (code);
CREATE INDEX idx_url_mappings_expires_at ON url_mappings (expires_at);
```

### Column Descriptions

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL` | Auto-increment primary key |
| `code` | `VARCHAR(6)` | Unique short code, `[A-Za-z0-9]` |
| `original_url` | `TEXT` | The full URL submitted by the user |
| `created_at` | `TIMESTAMPTZ` | UTC timestamp of record creation |
| `expires_at` | `TIMESTAMPTZ` | UTC timestamp 30 days after `created_at` |

### Migration Strategy

Use **Flyway** (included via `spring-boot-starter-data-jpa` + `flyway-core` dependency).
Migration file `V1__create_url_mappings.sql` runs automatically on application startup.
Set `spring.flyway.enabled=true` (default) and `spring.jpa.hibernate.ddl-auto=validate` to prevent JPA from managing DDL directly.

---

## 5. API Changes

This is a new service; all endpoints are new.

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=here"
}
```

**Responses**

| Status | Condition | Body |
|--------|-----------|------|
| 201 Created | URL valid, code generated | `{ "code": "aB3xY9", "shortUrl": "http://localhost:8080/aB3xY9" }` |
| 400 Bad Request | Missing or malformed `url` field | `{ "error": "Invalid URL", "message": "..." }` |
| 500 Internal Server Error | All collision retries exhausted | `{ "error": "Internal Server Error", "message": "An unexpected error occurred." }` |

### GET /{code}

**Request**
```
GET /aB3xY9
```

**Responses**

| Status | Condition | Body / Header |
|--------|-----------|---------------|
| 302 Found | Code exists and not expired | `Location: https://www.example.com/...` (no body) |
| 404 Not Found | Code never existed | `{ "error": "Not Found", "message": "No short URL found for code: aB3xY9" }` |
| 410 Gone | Code existed but is expired | `{ "error": "Gone", "message": "The short URL for code aB3xY9 has expired." }` |

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Scaffold the Maven project
- **Files:** `pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd`
- **Details:**
  - Spring Boot parent `3.3.x` (latest stable 3.x).
  - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `postgresql` (runtime), `flyway-core`, `spring-boot-starter-test` (test scope).
  - Java version: 17. Packaging: jar.
  - Add Maven Wrapper via `mvn wrapper:wrapper`.
- **Dependencies:** None.

### Task 2 — Application properties
- **Files:** `src/main/resources/application.properties`
- **Contents:**
  ```properties
  spring.datasource.url=jdbc:postgresql://localhost:5432/urlshortener
  spring.datasource.username=postgres
  spring.datasource.password=postgres
  spring.jpa.hibernate.ddl-auto=validate
  spring.jpa.properties.hibernate.jdbc.time_zone=UTC
  spring.flyway.enabled=true
  app.base-url=http://localhost:8080
  management.endpoints.web.exposure.include=health
  management.endpoint.health.show-details=always
  ```
- **Dependencies:** Task 1.

### Task 3 — Database migration script
- **Files:** `src/main/resources/db/migration/V1__create_url_mappings.sql`
- **Details:** Create `url_mappings` table with unique constraint on `code` and indexes on `code` and `expires_at`.
- **Dependencies:** Task 1.

### Task 4 — JPA Entity
- **Files:** `src/main/java/com/example/urlshortener/model/UrlMapping.java`
- **Details:**
  - Annotate with `@Entity @Table(name = "url_mappings")`.
  - `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id`.
  - `@Column(nullable = false, unique = true, length = 6) private String code`.
  - `@Column(name = "original_url", nullable = false, columnDefinition = "TEXT") private String originalUrl`.
  - `@Column(name = "created_at", nullable = false) private OffsetDateTime createdAt`.
  - `@Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt`.
  - Include no-arg constructor, all-args constructor, and getters/setters (or use Lombok `@Data` + `@Builder`).
- **Dependencies:** Task 3.

### Task 5 — Repository interface
- **Files:** `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java`
- **Details:** Declare `Optional<UrlMapping> findByCode(String code)`.
- **Dependencies:** Task 4.

### Task 6 — DTOs and exception classes
- **Files:**
  - `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
  - `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`
  - `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`
  - `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`
  - `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`
  - `src/main/java/com/example/urlshortener/exception/InvalidUrlException.java`
- **Dependencies:** None (pure POJOs).

### Task 7 — CodeGenerator utility
- **Files:** `src/main/java/com/example/urlshortener/util/CodeGenerator.java`
- **Details:**
  ```java
  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int CODE_LENGTH = 6;
  private final SecureRandom random = new SecureRandom();

  public String generate() {
      StringBuilder sb = new StringBuilder(CODE_LENGTH);
      for (int i = 0; i < CODE_LENGTH; i++) {
          sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
      }
      return sb.toString();
  }
  ```
- **Dependencies:** None.

### Task 8 — Service layer
- **Files:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
- **Details:** Implement `shorten(String url)` and `resolveCode(String code)` as described in Section 2.8. Use `@Transactional` on `shorten()`. Use SLF4J for structured logging.
- **Dependencies:** Tasks 5, 6, 7.

### Task 9 — Global exception handler
- **Files:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
- **Details:** Map all domain exceptions and `MethodArgumentNotValidException` to `ResponseEntity<ErrorResponse>` with the correct HTTP status codes (400, 404, 410, 500).
- **Dependencies:** Task 6.

### Task 10 — REST controller
- **Files:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
- **Details:** Implement `POST /shorten` (201) and `GET /{code}` (302) as described in Section 2.9. Inject `UrlShortenerService`.
- **Dependencies:** Tasks 6, 8, 9.

### Task 11 — Application main class
- **Files:** `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
- **Details:** `@SpringBootApplication public class UrlShortenerApplication { public static void main(String[] args) { SpringApplication.run(...); } }`
- **Dependencies:** Tasks 1–10 (all beans must be in place).

### Task 12 — Unit tests: CodeGenerator
- **Files:** `src/test/java/com/example/urlshortener/util/CodeGeneratorTest.java`
- **Details:** See Section 2.12.
- **Dependencies:** Task 7.

### Task 13 — Unit tests: UrlShortenerService
- **Files:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
- **Details:** See Section 2.12. Use `@ExtendWith(MockitoExtension.class)`.
- **Dependencies:** Task 8.

### Task 14 — Unit tests: UrlShortenerController
- **Files:** `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`
- **Details:** See Section 2.12. Use `@WebMvcTest(UrlShortenerController.class)` with `@MockBean UrlShortenerService`.
- **Dependencies:** Tasks 10, 13.

### Task 15 — CI configuration
- **Files:** `.github/workflows/ci.yml`
- **Details:**
  - Trigger: push to `main` and PRs.
  - Steps: checkout, set up JDK 17, run `./mvnw test`.
  - No live database needed for unit tests (all mocked).
- **Dependencies:** Tasks 12–14.

---

## 7. Testing Strategy

### Unit Tests (no live database required)

| Test Class | Scenarios |
|-----------|-----------|
| `CodeGeneratorTest` | Code is exactly 6 chars; code matches `[A-Za-z0-9]{6}`; 1000 samples show randomness (no single value repeated >50 times) |
| `UrlShortenerServiceTest` | Valid URL → shorten success; invalid URL → `InvalidUrlException`; collision retry → retries and succeeds on 2nd attempt; all 5 retries fail → `RuntimeException`; active code → returns URL; unknown code → `CodeNotFoundException`; expired code → `CodeExpiredException` |
| `UrlShortenerControllerTest` | `POST /shorten` 201 response shape; `POST /shorten` 400 missing field; `POST /shorten` 400 invalid URL; `GET /{code}` 302 Location header; `GET /{code}` 404 JSON body; `GET /{code}` 410 JSON body |

### Edge Cases to Cover

- URL with special characters and query parameters (must not be rejected if well-formed).
- URL with `ftp://` scheme → must return 400 (only `http`/`https` accepted).
- Code with mixed case (`aB3xY9`) — lookup must be case-sensitive.
- `expires_at` is exactly `now()` (boundary): must return 410, not 302.
- `url` field sent as `null` → 400 from Bean Validation before reaching service.
- Empty string `url` → 400.

### Integration Tests (optional, future iteration)

Use **Testcontainers** (`org.testcontainers:postgresql`) for a real PostgreSQL instance:
- Full round-trip: `POST /shorten` → `GET /{returned code}` → 302.
- Expired record: insert with `expires_at` in the past → `GET` → 410.
- Duplicate `POST` of same URL → two different codes returned.

Place these in `src/test/java/…/integration/` and tag with JUnit 5 `@Tag("integration")` so they can be excluded from the standard `./mvnw test` run and run separately via `./mvnw test -Dgroups=integration`.

---

## 8. Risk Assessment

### Technical Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|-----------|
| R-1 | **Code collision** — 62^6 ≈ 56.8B combinations; at low scale collision probability is negligible | Low | Medium | `UNIQUE` constraint on `code` column; catch `DataIntegrityViolationException` and retry up to 5 times before returning 500 |
| R-2 | **URL validation too strict/lenient** — `java.net.URL` accepts some unusual schemes | Medium | Medium | Explicitly check scheme is `http` or `https` after parsing; use `java.net.URI` for format validation (stricter than `URL`) |
| R-3 | **Clock skew across instances** — expiry checks might be inconsistent | Low | Low | Store and compare timestamps in UTC (`OffsetDateTime.now(ZoneOffset.UTC)`); set `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` |
| R-4 | **Flyway migration not applied in CI** — tests against a live DB fail if schema is missing | Medium | High | Unit tests are fully mocked (no DB needed); Flyway runs on application startup for integration/manual tests |
| R-5 | **`shortUrl` host incorrect behind proxy** | Medium | Medium | Expose `app.base-url` property; document that operators must set it to the public hostname in production |
| R-6 | **Table growth with expired records** | Low (short-term) | Medium (long-term) | Index on `expires_at`; note in README that a future `@Scheduled` cleanup job should purge expired rows |

### Dependency Risks

- **Spring Boot 3.x requires Java 17+** — confirm CI runner has JDK 17.
- **Flyway version compatibility** — use `flyway-core` version managed by Spring Boot BOM to avoid version conflicts.
- **PostgreSQL driver** — `org.postgresql:postgresql` must be on runtime classpath; confirm it is **not** marked `<scope>test</scope>`.

### Rollback Plan

Since this is a net-new service with no existing traffic:
1. Stop/remove the deployed container or process.
2. Drop the `url_mappings` table (`DROP TABLE url_mappings CASCADE;`) if the schema was applied.
3. Remove the Flyway migration file from version control if the schema must be re-designed.
4. The migration file is `V1__…` — if a hotfix schema change is needed before any data is in production, delete the `flyway_schema_history` record and re-run with an updated `V1` (only safe on a fresh database).

No other services are affected since this is a standalone greenfield deployment.

---

## Appendix: Full File Tree

```
url-shortener/
├── .github/
│   └── workflows/
│       └── ci.yml
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/urlshortener/
│   │   │   ├── UrlShortenerApplication.java
│   │   │   ├── controller/
│   │   │   │   └── UrlShortenerController.java
│   │   │   ├── dto/
│   │   │   │   ├── ShortenRequest.java
│   │   │   │   ├── ShortenResponse.java
│   │   │   │   └── ErrorResponse.java
│   │   │   ├── exception/
│   │   │   │   ├── CodeExpiredException.java
│   │   │   │   ├── CodeNotFoundException.java
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   └── InvalidUrlException.java
│   │   │   ├── model/
│   │   │   │   └── UrlMapping.java
│   │   │   ├── repository/
│   │   │   │   └── UrlMappingRepository.java
│   │   │   ├── service/
│   │   │   │   └── UrlShortenerService.java
│   │   │   └── util/
│   │   │       └── CodeGenerator.java
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/
│   │           └── V1__create_url_mappings.sql
│   └── test/
│       └── java/com/example/urlshortener/
│           ├── controller/
│           │   └── UrlShortenerControllerTest.java
│           ├── service/
│           │   └── UrlShortenerServiceTest.java
│           └── util/
│               └── CodeGeneratorTest.java
├── artifacts/
│   ├── prd.md
│   └── plan.md
└── docs/
    └── prd.md
```

---

*End of Implementation Plan*
