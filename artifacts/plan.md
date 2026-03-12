# Implementation Plan: URL Shortener Service

**Date:** 2026-03-12
**Branch:** `ai-sdlc/ai-sdlc-1773295855`
**PRD Reference:** `artifacts/prd.md`

---

## 1. Architecture Overview

This is a **greenfield** Spring Boot 3.x REST API. There is no existing application code; the entire service is built from scratch.

```
┌──────────────────────────────────────────────────────────────┐
│                        HTTP Client                           │
└──────────────┬───────────────────────────┬───────────────────┘
               │ POST /shorten             │ GET /{code}
               ▼                           ▼
┌──────────────────────────────────────────────────────────────┐
│                  UrlShortenerController                      │
│  (Spring MVC @RestController)                                │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                    UrlShortenerService                       │
│  - generateCode()       (random 6-char alphanumeric)         │
│  - shorten(url, host)   (persist + return response DTO)      │
│  - resolve(code)        (lookup + expiry check)              │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                  ShortUrlRepository                          │
│  (Spring Data JPA, extends JpaRepository)                    │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│              PostgreSQL — table: short_urls                  │
│  id | code | original_url | created_at | expires_at          │
└──────────────────────────────────────────────────────────────┘
```

**Key design decisions:**
- Single Spring Boot module (no multi-module Maven structure needed for this scope).
- Flyway manages the database schema migration.
- Spring Data JPA with Hibernate handles persistence.
- Expiry check performed in the Java service layer using `Instant.now()` (consistent within the JVM, avoids clock-skew issues with DB).
- Base URL for `shortUrl` construction read from `app.base-url` configuration property; fails fast on startup if absent.
- Collision retry loop capped at 10 attempts; throws `IllegalStateException` if exhausted (mapped to HTTP 500).

---

## 2. Files to Create

### 2.1 Build Configuration

#### `pom.xml`
- **Purpose:** Maven build descriptor for the project.
- **Key contents:**
  - `groupId`: `com.example`, `artifactId`: `url-shortener`
  - `parent`: `spring-boot-starter-parent` 3.2.x
  - Java 17 source/target
  - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `postgresql` (runtime), `flyway-core`, `spring-boot-starter-validation`, `spring-boot-starter-test` (test scope)

---

### 2.2 Application Entry Point

#### `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
- **Purpose:** `@SpringBootApplication` main class.
- **Key contents:**
  ```java
  @SpringBootApplication
  public class UrlShortenerApplication {
      public static void main(String[] args) {
          SpringApplication.run(UrlShortenerApplication.class, args);
      }
  }
  ```

---

### 2.3 Configuration

#### `src/main/resources/application.yml`
- **Purpose:** Externalised application configuration.
- **Key contents:**
  ```yaml
  server:
    port: 8080

  spring:
    datasource:
      url: ${DB_URL}
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}
      driver-class-name: org.postgresql.Driver
    jpa:
      hibernate:
        ddl-auto: validate
      show-sql: false
    flyway:
      enabled: true

  app:
    base-url: ${APP_BASE_URL}          # e.g. http://localhost:8080
    expiry-days: 30
    max-code-retries: 10

  management:
    endpoints:
      web:
        base-path: /actuator
    endpoint:
      health:
        show-details: always
  ```
  - Credentials are sourced exclusively from environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BASE_URL`). No hardcoded values.

---

### 2.4 Domain Entity

#### `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
- **Purpose:** JPA entity mapping to the `short_urls` table.
- **Key contents:**
  ```java
  @Entity
  @Table(name = "short_urls")
  public class ShortUrl {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      @Column(nullable = false, unique = true, length = 6)
      private String code;

      @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
      private String originalUrl;

      @Column(name = "created_at", nullable = false)
      private Instant createdAt;

      @Column(name = "expires_at", nullable = false)
      private Instant expiresAt;

      // constructors, getters, setters
  }
  ```

---

### 2.5 Repository

#### `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`
- **Purpose:** Spring Data JPA repository for `ShortUrl`.
- **Key contents:**
  ```java
  public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
      Optional<ShortUrl> findByCode(String code);
      boolean existsByCode(String code);
  }
  ```
  - `findByCode` — used on redirect lookup.
  - `existsByCode` — used in collision detection loop.

---

### 2.6 DTOs

#### `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
- **Purpose:** Request body for `POST /shorten`.
- **Key contents:**
  ```java
  public record ShortenRequest(
      @NotBlank(message = "Field 'url' is required and must not be empty.")
      String url
  ) {}
  ```

#### `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`
- **Purpose:** Response body for `POST /shorten`.
- **Key contents:**
  ```java
  public record ShortenResponse(String code, String shortUrl) {}
  ```

#### `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`
- **Purpose:** Uniform error envelope for 4xx/5xx responses.
- **Key contents:**
  ```java
  public record ErrorResponse(String error) {}
  ```

---

### 2.7 Service

#### `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
- **Purpose:** Core business logic — code generation, persistence, and redirect resolution.
- **Key contents / methods:**

  ```java
  @Service
  public class UrlShortenerService {

      private static final String ALPHABET =
          "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
      private static final int CODE_LENGTH = 6;

      @Value("${app.base-url}")
      private String baseUrl;

      @Value("${app.expiry-days:30}")
      private int expiryDays;

      @Value("${app.max-code-retries:10}")
      private int maxRetries;

      private final ShortUrlRepository repository;
      private final SecureRandom random = new SecureRandom();

      // shorten(String originalUrl): ShortenResponse
      //   - retry loop up to maxRetries
      //   - generates 6-char code via generateCode()
      //   - checks existsByCode(); regenerates on collision
      //   - saves ShortUrl entity with createdAt=now, expiresAt=now+expiryDays
      //   - returns ShortenResponse(code, baseUrl + "/" + code)
      //   - throws IllegalStateException if retry limit exceeded

      // resolve(String code): ShortUrl
      //   - findByCode(code) — throws CodeNotFoundException (404) if absent
      //   - checks expiresAt.isAfter(Instant.now()) — throws CodeExpiredException (410) if expired
      //   - returns ShortUrl

      // generateCode(): String
      //   - builds 6-char string by sampling ALPHABET with SecureRandom
  }
  ```

---

### 2.8 Custom Exceptions

#### `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`
- **Purpose:** Signals unknown short code (maps to HTTP 404).
- **Key contents:**
  ```java
  public class CodeNotFoundException extends RuntimeException {
      public CodeNotFoundException(String code) {
          super("Short code not found: " + code);
      }
  }
  ```

#### `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`
- **Purpose:** Signals expired short code (maps to HTTP 410).
- **Key contents:**
  ```java
  public class CodeExpiredException extends RuntimeException {
      public CodeExpiredException(String code) {
          super("Short code has expired: " + code);
      }
  }
  ```

---

### 2.9 Controller

#### `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
- **Purpose:** REST controller exposing the two API endpoints.
- **Key contents:**
  ```java
  @RestController
  public class UrlShortenerController {

      private final UrlShortenerService service;

      // POST /shorten
      // @RequestBody @Valid ShortenRequest → ResponseEntity<ShortenResponse>
      // Logs: INFO "Shortened [originalUrl] → [code]"
      // Returns 200 OK with ShortenResponse

      // GET /{code}
      // @PathVariable String code → ResponseEntity<Void>
      // Calls service.resolve(code)
      // Returns 302 with Location header set to originalUrl
      // Logs: INFO "Redirect [code] → [originalUrl]"
  }
  ```

---

### 2.10 Global Exception Handler

#### `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
- **Purpose:** `@RestControllerAdvice` that maps exceptions to HTTP responses without leaking stack traces.
- **Key contents / mappings:**

  | Exception | HTTP Status | Body |
  |-----------|-------------|------|
  | `MethodArgumentNotValidException` | 400 | `ErrorResponse` with first constraint message |
  | `CodeNotFoundException` | 404 | `ErrorResponse` |
  | `CodeExpiredException` | 410 | `ErrorResponse` |
  | `IllegalStateException` (retry exhausted) | 500 | `ErrorResponse("Service temporarily unavailable.")` |
  | `DataAccessException` (DB failure) | 503 | `ErrorResponse("Service temporarily unavailable.")` |
  | `Exception` (catch-all) | 500 | `ErrorResponse("An unexpected error occurred.")` |

---

### 2.11 Database Migration

#### `src/main/resources/db/migration/V1__create_short_urls.sql`
- **Purpose:** Flyway migration to create the initial schema.
- **Key contents:**
  ```sql
  CREATE TABLE short_urls (
      id          BIGSERIAL    PRIMARY KEY,
      code        VARCHAR(6)   NOT NULL UNIQUE,
      original_url TEXT        NOT NULL,
      created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
      expires_at  TIMESTAMPTZ  NOT NULL
  );

  CREATE INDEX idx_short_urls_code ON short_urls (code);
  ```

---

### 2.12 Unit Tests

#### `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
- **Purpose:** Unit tests for `UrlShortenerService` with a mocked `ShortUrlRepository`.
- **Test cases:** See Section 7.

#### `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`
- **Purpose:** `@WebMvcTest` slice tests for the controller layer.
- **Test cases:** See Section 7.

---

## 3. Files to Modify

There are **no existing source files to modify**. This is a greenfield project. The only pre-existing files are:

| File | Current state | Action |
|------|---------------|--------|
| `README.md` | Single blank line | Update with project overview, setup instructions, and environment variable documentation after implementation. |
| `artifacts/prd.md` | PRD document | No modification needed. |

---

## 4. Database Schema Changes

### New Table: `short_urls`

```sql
CREATE TABLE short_urls (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL UNIQUE,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
```

### Migration Management

- Tool: **Flyway**
- Script location: `src/main/resources/db/migration/V1__create_short_urls.sql`
- Flyway runs automatically on application startup.
- `spring.jpa.hibernate.ddl-auto=validate` ensures Hibernate validates the schema against the entity model but does not auto-create or alter tables.

### Required Environment for Migrations

A live PostgreSQL instance (or Testcontainers in CI) is required. Flyway will fail fast and prevent application startup if the migration cannot run, which surfaces schema errors early.

---

## 5. API Changes

This is a new service with no prior endpoints.

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

**Response — 400 Bad Request** (missing/empty `url`)
```json
{
  "error": "Field 'url' is required and must not be empty."
}
```

**Response — 500 Internal Server Error** (code generation retry limit exceeded)
```json
{
  "error": "Service temporarily unavailable."
}
```

---

### GET /{code}

| Scenario | HTTP Status | Response |
|----------|-------------|----------|
| Code found, not expired | 302 Found | `Location: <original_url>`, empty body |
| Code found, expired | 410 Gone | `{"error": "Short code has expired: <code>"}` |
| Code not found | 404 Not Found | `{"error": "Short code not found: <code>"}` |
| DB unavailable | 503 Service Unavailable | `{"error": "Service temporarily unavailable."}` |

---

### GET /actuator/health

- Enabled via Spring Boot Actuator.
- Reports `UP`/`DOWN` including database connectivity.
- Returns 200 when healthy, 503 when DB is unreachable.

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Initialise Maven project structure

**Files affected:** `pom.xml`, directory structure

Create the standard Maven directory layout:
```
url-shortener/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/urlshortener/
    │   └── resources/
    │       └── db/migration/
    └── test/
        └── java/com/example/urlshortener/
```

Create `pom.xml` with:
- Parent: `spring-boot-starter-parent:3.2.x`
- Java 17
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `postgresql` (runtime), `flyway-core`, `spring-boot-starter-test`

**Dependencies:** None (first task).

---

### Task 2 — Write the Flyway migration script

**Files affected:** `src/main/resources/db/migration/V1__create_short_urls.sql`

Create the `short_urls` table and `idx_short_urls_code` index exactly as specified in Section 4.

**Dependencies:** Task 1 (directory structure exists).

---

### Task 3 — Write `application.yml`

**Files affected:** `src/main/resources/application.yml`

Configure datasource (via env vars), JPA/Hibernate `ddl-auto: validate`, Flyway, `app.base-url`, `app.expiry-days`, `app.max-code-retries`, and Actuator.

**Dependencies:** Task 1.

---

### Task 4 — Create the `ShortUrl` entity

**Files affected:** `src/main/java/com/example/urlshortener/entity/ShortUrl.java`

Implement the JPA entity with `@Entity`, `@Table(name="short_urls")`, and all five columns mapped to their SQL counterparts using `Instant` for timestamp fields.

**Dependencies:** Task 1, Task 2 (entity must match migration schema).

---

### Task 5 — Create `ShortUrlRepository`

**Files affected:** `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`

Extend `JpaRepository<ShortUrl, Long>`. Declare `findByCode(String code)` and `existsByCode(String code)`.

**Dependencies:** Task 4.

---

### Task 6 — Create DTOs

**Files affected:**
- `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
- `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`
- `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`

Implement as Java records. Annotate `ShortenRequest.url` with `@NotBlank`.

**Dependencies:** Task 1.

---

### Task 7 — Create custom exceptions

**Files affected:**
- `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`
- `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`

Both extend `RuntimeException` with a message including the offending code.

**Dependencies:** Task 1.

---

### Task 8 — Implement `UrlShortenerService`

**Files affected:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`

Implement the three methods:

1. **`generateCode()`**
   ```
   char[] chars = new char[6];
   for (int i = 0; i < 6; i++) {
       chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
   }
   return new String(chars);
   ```

2. **`shorten(String originalUrl)`**
   ```
   for (int attempt = 0; attempt < maxRetries; attempt++) {
       String code = generateCode();
       if (!repository.existsByCode(code)) {
           Instant now = Instant.now();
           ShortUrl entity = new ShortUrl(code, originalUrl, now, now.plus(expiryDays, DAYS));
           repository.save(entity);
           log.info("Shortened {} → {}", originalUrl, code);
           return new ShortenResponse(code, baseUrl + "/" + code);
       }
   }
   throw new IllegalStateException("Could not generate a unique code after " + maxRetries + " attempts");
   ```

3. **`resolve(String code)`**
   ```
   ShortUrl shortUrl = repository.findByCode(code)
       .orElseThrow(() -> new CodeNotFoundException(code));
   if (!shortUrl.getExpiresAt().isAfter(Instant.now())) {
       throw new CodeExpiredException(code);
   }
   log.info("Redirect {} → {}", code, shortUrl.getOriginalUrl());
   return shortUrl;
   ```

**Dependencies:** Tasks 5, 6, 7.

---

### Task 9 — Implement `UrlShortenerController`

**Files affected:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`

```java
@RestController
public class UrlShortenerController {

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(
            @RequestBody @Valid ShortenRequest request) {
        return ResponseEntity.ok(service.shorten(request.url()));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortUrl shortUrl = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(shortUrl.getOriginalUrl()))
            .build();
    }
}
```

**Dependencies:** Task 8.

---

### Task 10 — Implement `GlobalExceptionHandler`

**Files affected:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`

`@RestControllerAdvice` with `@ExceptionHandler` methods as described in Section 2.10. Ensure stack traces are never included in response bodies.

**Dependencies:** Tasks 6, 7.

---

### Task 11 — Create the application entry point

**Files affected:** `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`

Standard `@SpringBootApplication` main class.

**Dependencies:** Task 1.

---

### Task 12 — Write unit tests for `UrlShortenerService`

**Files affected:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

See Section 7 for full test case list.

**Dependencies:** Task 8.

---

### Task 13 — Write `@WebMvcTest` tests for `UrlShortenerController`

**Files affected:** `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`

See Section 7 for full test case list.

**Dependencies:** Tasks 9, 10.

---

### Task 14 — Verify the build compiles and all tests pass

Run `./mvnw clean verify` (or `mvn clean verify`). All unit tests must pass. Flyway migration tests (if using Testcontainers) must pass.

**Dependencies:** All prior tasks.

---

## 7. Testing Strategy

### 7.1 Unit Tests — `UrlShortenerServiceTest`

Use `@ExtendWith(MockitoExtension.class)`. Mock `ShortUrlRepository` with `@Mock`. Inject via `@InjectMocks`.

| # | Test Method | Description |
|---|-------------|-------------|
| 1 | `generateCode_matchesAlphanumericPattern` | Call `generateCode()` 1000 times; assert each result matches `[A-Za-z0-9]{6}`. |
| 2 | `generateCode_producesDistinctCodes` | Call `generateCode()` 100 times; assert the set size is > 1 (codes are not all identical). |
| 3 | `shorten_savesEntityAndReturnsResponse` | Mock `existsByCode` → `false`. Call `shorten("https://example.com")`. Assert `repository.save()` called once. Assert returned `code` matches pattern and `shortUrl` starts with configured base URL. |
| 4 | `shorten_retriesOnCollisionAndSucceeds` | Mock `existsByCode` to return `true` on first call, `false` on second. Assert `existsByCode` called twice and `save` called once. |
| 5 | `shorten_throwsIllegalStateExceptionWhenRetriesExhausted` | Mock `existsByCode` → always `true`. Assert `IllegalStateException` thrown. |
| 6 | `shorten_duplicateLongUrlsProduceDistinctCodes` | Call `shorten` twice with same URL; mock different codes returned each time. Assert two distinct codes returned. |
| 7 | `resolve_returnsShortUrlWhenValidAndNotExpired` | Mock `findByCode` → entity with `expiresAt = Instant.now().plus(1, DAYS)`. Assert entity returned without exception. |
| 8 | `resolve_throwsCodeExpiredExceptionWhenExpired` | Mock `findByCode` → entity with `expiresAt = Instant.now().minus(1, DAYS)`. Assert `CodeExpiredException` thrown. |
| 9 | `resolve_throwsCodeNotFoundExceptionWhenAbsent` | Mock `findByCode` → `Optional.empty()`. Assert `CodeNotFoundException` thrown. |

---

### 7.2 Controller Slice Tests — `UrlShortenerControllerTest`

Use `@WebMvcTest(UrlShortenerController.class)`. Mock `UrlShortenerService` with `@MockBean`.

| # | Test Method | Description |
|---|-------------|-------------|
| 1 | `postShorten_returns200WithCodeAndShortUrl` | Mock service to return `ShortenResponse("abc123", "http://localhost/abc123")`. POST `/shorten` with `{"url":"https://example.com"}`. Assert 200, `code="abc123"`, `shortUrl="http://localhost/abc123"`. |
| 2 | `postShorten_returns400WhenUrlMissing` | POST `/shorten` with `{}`. Assert 400, response body contains `"error"` key. |
| 3 | `postShorten_returns400WhenUrlEmpty` | POST `/shorten` with `{"url":""}`. Assert 400. |
| 4 | `getCode_returns302WithLocationHeader` | Mock `service.resolve("abc123")` → entity with `originalUrl="https://example.com"`. GET `/abc123`. Assert 302, `Location: https://example.com`. |
| 5 | `getCode_returns404WhenCodeNotFound` | Mock `service.resolve` → throws `CodeNotFoundException`. GET `/abc123`. Assert 404. |
| 6 | `getCode_returns410WhenCodeExpired` | Mock `service.resolve` → throws `CodeExpiredException`. GET `/abc123`. Assert 410. |

---

### 7.3 Edge Cases to Cover

| Edge Case | Handled By |
|-----------|------------|
| Empty string `url` field | `@NotBlank` validation → 400 |
| Missing `url` field entirely | `@NotBlank` validation → 400 |
| Code exactly at expiry boundary (`expiresAt == Instant.now()`) | `isAfter` check: boundary is treated as expired → 410 |
| All 10 collision retries exhausted | `IllegalStateException` → `GlobalExceptionHandler` → 500 |
| DB unreachable during save | `DataAccessException` → `GlobalExceptionHandler` → 503 |
| Code with non-alphanumeric characters in path | Spring MVC path variable binding; returns 404 since no such code exists |

---

## 8. Risk Assessment

### 8.1 Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **Code collision under concurrent writes** | Low | Medium | Unique DB constraint on `code` is the final guard. Retry loop handles application-level collisions. Log collisions at WARN level for monitoring. If `DataIntegrityViolationException` is thrown on save (race condition), catch it in the service and retry. |
| **Clock skew between JVM and DB** | Low | Low | Expiry check uses `Instant.now()` in the JVM; consistent within a single request. Documented in code comments. |
| **Flyway migration failure on startup** | Low | High | Test migration scripts locally against PostgreSQL before committing. Validate in CI using Testcontainers or a dedicated PostgreSQL service container. |
| **`APP_BASE_URL` misconfiguration** | Medium | Medium | Bind to `@Value("${app.base-url}")` with no default; Spring will fail to start if the property is absent. Document clearly in `README.md`. |
| **Open redirect abuse** | Medium | Medium | Out of scope for initial implementation per PRD. Document as a known risk. Future enhancement: domain allow-listing. |
| **Unbounded table growth** | Medium | Low | Expired records are not purged. Acceptable for initial scope. Document a follow-up task: add a `@Scheduled` cleanup job or DB cron to `DELETE FROM short_urls WHERE expires_at < NOW()`. |
| **`DataIntegrityViolationException` on concurrent insert** | Low | Low | Catch `DataIntegrityViolationException` (subtype of `DataAccessException`) in the retry loop and treat as a collision; retry with a new code. |

---

### 8.2 Dependency Risks

| Dependency | Risk | Mitigation |
|------------|------|------------|
| PostgreSQL availability | Service is non-functional without DB | Actuator health endpoint surfaces DB status; return 503 on `DataAccessException`. |
| Spring Boot 3.x | Requires Java 17+ | Enforce `<java.version>17</java.version>` in `pom.xml`. |
| Flyway | Breaking migration stops startup | Keep migrations additive; never edit committed migration scripts. |

---

### 8.3 Rollback Plan

Since this is a greenfield service with no traffic to migrate:

1. **Code rollback:** Revert the feature branch; the service simply does not exist yet.
2. **Database rollback:** Drop the `short_urls` table manually or via a `flyway repair` + reverse migration (`V2__drop_short_urls.sql`) if Flyway has already run.
3. **Partial deployment rollback:** The service is stateless; stopping the process and rolling back the container/deployment is sufficient. No distributed state to clean up.

---

## Appendix A: Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5432/urlshortener` |
| `DB_USERNAME` | Database username | `appuser` |
| `DB_PASSWORD` | Database password | *(secret)* |
| `APP_BASE_URL` | Base URL for constructing short URLs | `http://localhost:8080` |

---

## Appendix B: File Tree (Final)

```
url-shortener/
├── pom.xml
├── README.md
├── artifacts/
│   ├── prd.md
│   └── plan.md
└── src/
    ├── main/
    │   ├── java/com/example/urlshortener/
    │   │   ├── UrlShortenerApplication.java
    │   │   ├── controller/
    │   │   │   └── UrlShortenerController.java
    │   │   ├── dto/
    │   │   │   ├── ErrorResponse.java
    │   │   │   ├── ShortenRequest.java
    │   │   │   └── ShortenResponse.java
    │   │   ├── entity/
    │   │   │   └── ShortUrl.java
    │   │   ├── exception/
    │   │   │   ├── CodeExpiredException.java
    │   │   │   ├── CodeNotFoundException.java
    │   │   │   └── GlobalExceptionHandler.java
    │   │   ├── repository/
    │   │   │   └── ShortUrlRepository.java
    │   │   └── service/
    │   │       └── UrlShortenerService.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__create_short_urls.sql
    └── test/
        └── java/com/example/urlshortener/
            ├── controller/
            │   └── UrlShortenerControllerTest.java
            └── service/
                └── UrlShortenerServiceTest.java
```
