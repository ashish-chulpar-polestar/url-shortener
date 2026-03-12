# Implementation Plan: URL Shortener Service

**Date:** 2026-03-12
**Branch:** main
**PRD Reference:** artifacts/prd.md

---

## 1. Architecture Overview

This is a greenfield Spring Boot 3.x REST API backed by PostgreSQL. There is no existing codebase to integrate with.

### Component Diagram

```
┌─────────────────────────────────────────────────────┐
│                  HTTP Client / Browser               │
└──────────────┬────────────────────────┬─────────────┘
               │ POST /shorten          │ GET /{code}
               ▼                        ▼
┌─────────────────────────────────────────────────────┐
│              UrlShortenerController                  │
│  - validate request (ShortenRequest DTO + @Valid)    │
│  - map exceptions → HTTP status via @ControllerAdvice│
└──────────────┬────────────────────────┬─────────────┘
               │                        │
               ▼                        ▼
┌─────────────────────────────────────────────────────┐
│              UrlShortenerService                     │
│  - generateCode()  — random 6-char alphanumeric      │
│  - shorten()       — retry loop, persist record      │
│  - resolve()       — lookup + expiry check           │
└──────────────────────────┬──────────────────────────┘
                           │ Spring Data JPA
                           ▼
┌─────────────────────────────────────────────────────┐
│              ShortUrlRepository (JpaRepository)      │
└──────────────────────────┬──────────────────────────┘
                           │ HikariCP
                           ▼
┌─────────────────────────────────────────────────────┐
│  PostgreSQL — table: short_urls                      │
│  Managed by Flyway migrations                        │
└─────────────────────────────────────────────────────┘
```

### Key Design Decisions

- **Spring Boot 3.3.x** with Java 21 (LTS).
- **Spring Data JPA** (Hibernate) for repository layer; parameterized queries prevent SQL injection by default.
- **Flyway** for versioned, reproducible schema migrations.
- **`app.base-url`** configuration property for constructing `shortUrl` behind proxies.
- **`SecureRandom`** used for code generation (cryptographically strong randomness).
- **Retry loop (max 5 attempts)** on `DataIntegrityViolationException` from the unique constraint on `code`.
- **`@ControllerAdvice`** global exception handler maps domain exceptions and Spring exceptions to correct HTTP status codes without leaking internals.
- **Spring Boot Actuator** exposed for `/actuator/health` and `/actuator/info`.

---

## 2. Files to Create

### Project Root

#### `pom.xml`
- **Purpose:** Maven build descriptor. Declares all dependencies and plugins.
- **Key contents:**
  - Parent: `spring-boot-starter-parent` 3.3.x
  - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `postgresql` (runtime), `flyway-core`, `flyway-database-postgresql`
  - Test dependencies: `spring-boot-starter-test`, `h2` (in-memory DB for unit tests)
  - Plugin: `spring-boot-maven-plugin` (fat JAR)
  - Java version: 21

---

### Application Entry Point

#### `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
- **Purpose:** Spring Boot main class.
- **Key contents:**
  - `@SpringBootApplication`
  - `main()` method calling `SpringApplication.run()`

---

### Configuration

#### `src/main/java/com/example/urlshortener/config/AppProperties.java`
- **Purpose:** Binds `app.base-url` from `application.yml` into a typed configuration bean.
- **Key contents:**
  - `@ConfigurationProperties(prefix = "app")`
  - `@Validated`
  - Field: `String baseUrl` — annotated `@NotBlank`
  - Standard getter/setter

#### `src/main/resources/application.yml`
- **Purpose:** Application configuration for all Spring and custom properties.
- **Key contents:**
  ```yaml
  spring:
    datasource:
      url: ${DB_URL:jdbc:postgresql://localhost:5432/urlshortener}
      username: ${DB_USER:postgres}
      password: ${DB_PASSWORD:postgres}
      hikari:
        connection-timeout: 3000        # 3s max wait for a connection
        maximum-pool-size: 10
        validation-timeout: 2000
    jpa:
      hibernate:
        ddl-auto: validate              # Flyway owns DDL; Hibernate only validates
      properties:
        hibernate:
          dialect: org.hibernate.dialect.PostgreSQLDialect
          format_sql: false
    flyway:
      enabled: true
      locations: classpath:db/migration
  app:
    base-url: ${APP_BASE_URL:http://localhost:8080}
  management:
    endpoints:
      web:
        exposure:
          include: health,info
  logging:
    pattern:
      console: '{"timestamp":"%d","level":"%p","logger":"%c{1}","message":"%m"}%n'
  ```

---

### Database Migration

#### `src/main/resources/db/migration/V1__create_short_urls.sql`
- **Purpose:** Flyway migration that creates the `short_urls` table.
- **Key contents:** See §4 below.

---

### Entity

#### `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
- **Purpose:** JPA entity mapping to the `short_urls` table.
- **Key contents:**
  - `@Entity`, `@Table(name = "short_urls")`
  - Fields:
    - `Long id` — `@Id @GeneratedValue(strategy = IDENTITY)`
    - `String code` — `@Column(nullable = false, unique = true, length = 6)`
    - `String originalUrl` — `@Column(name = "original_url", nullable = false, columnDefinition = "TEXT")`
    - `OffsetDateTime createdAt` — `@Column(name = "created_at", nullable = false)`
    - `OffsetDateTime expiresAt` — `@Column(name = "expires_at", nullable = false)`
  - No-arg constructor + all-args constructor / builder

---

### Repository

#### `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`
- **Purpose:** Spring Data JPA repository for `ShortUrl`.
- **Key contents:**
  - `public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long>`
  - `Optional<ShortUrl> findByCode(String code)`

---

### DTOs

#### `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
- **Purpose:** Request body for `POST /shorten`.
- **Key contents:**
  - Field: `String url`
  - Annotations: `@NotBlank`, `@Pattern(regexp = "https?://.+")` — enforces HTTP/HTTPS scheme and non-blank
  - Jackson `@JsonProperty("url")`

#### `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`
- **Purpose:** Response body for `POST /shorten`.
- **Key contents:**
  - Fields: `String code`, `String shortUrl`
  - Constructor + getters

#### `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`
- **Purpose:** Uniform error body for all 4xx/5xx responses.
- **Key contents:**
  - Field: `String error`
  - Constructor + getter

---

### Custom Exceptions

#### `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`
- **Purpose:** Thrown when `GET /{code}` finds no record; mapped to HTTP 404.
- **Key contents:** `extends RuntimeException`; constructor accepts the code string.

#### `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`
- **Purpose:** Thrown when `GET /{code}` finds a record but `expiresAt` is in the past; mapped to HTTP 410.
- **Key contents:** `extends RuntimeException`; constructor accepts the code string.

#### `src/main/java/com/example/urlshortener/exception/CodeGenerationException.java`
- **Purpose:** Thrown when all retry attempts for unique code generation are exhausted; mapped to HTTP 500.
- **Key contents:** `extends RuntimeException`.

---

### Service

#### `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
- **Purpose:** Core business logic for shortening and resolving URLs.
- **Key contents:**
  - `@Service`
  - Inject: `ShortUrlRepository`, `AppProperties`, `Clock` (for testable time)
  - **`String generateCode()`** — private method:
    - Alphabet: `"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"` (62 chars)
    - Use `SecureRandom` to pick 6 characters
    - Return 6-character string
  - **`ShortenResponse shorten(ShortenRequest request)`** — public method:
    - Loop up to 5 times:
      - Call `generateCode()`
      - Build `ShortUrl` entity with `createdAt = now(clock)`, `expiresAt = createdAt + 30 days`
      - Call `repository.save(entity)` inside try/catch `DataIntegrityViolationException`
      - On success: return `ShortenResponse(code, baseUrl + "/" + code)`
      - On `DataIntegrityViolationException`: continue loop
    - After 5 failures: throw `CodeGenerationException`
  - **`String resolve(String code)`** — public method:
    - Validate `code` matches `[A-Za-z0-9]{6}` (reject early with `CodeNotFoundException` for invalid format)
    - Call `repository.findByCode(code)` → `Optional<ShortUrl>`
    - If empty: throw `CodeNotFoundException(code)`
    - If `entity.getExpiresAt().isBefore(OffsetDateTime.now(clock))`: throw `CodeExpiredException(code)`
    - Return `entity.getOriginalUrl()`

---

### Controller

#### `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
- **Purpose:** Exposes `POST /shorten` and `GET /{code}` REST endpoints.
- **Key contents:**
  - `@RestController`
  - Inject: `UrlShortenerService`
  - **`POST /shorten`**:
    ```java
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        return ResponseEntity.ok(service.shorten(req));
    }
    ```
  - **`GET /{code}`**:
    ```java
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String originalUrl = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(originalUrl))
            .build();
    }
    ```

---

### Global Exception Handler

#### `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
- **Purpose:** Centralized mapping from exceptions to HTTP responses, preventing stack trace leakage.
- **Key contents:**
  - `@RestControllerAdvice`
  - `@ExceptionHandler(CodeNotFoundException.class)` → 404 + `ErrorResponse`
  - `@ExceptionHandler(CodeExpiredException.class)` → 410 + `ErrorResponse`
  - `@ExceptionHandler(MethodArgumentNotValidException.class)` → 400 + `ErrorResponse` (field validation failures from `@Valid`)
  - `@ExceptionHandler(DataAccessException.class)` → 503 + `ErrorResponse` (DB unavailable)
  - `@ExceptionHandler(Exception.class)` → 500 + `ErrorResponse("Internal server error")` (catch-all; no internals exposed)

---

### Spring Configuration Bean

#### `src/main/java/com/example/urlshortener/config/ClockConfig.java`
- **Purpose:** Provides a `Clock` bean (UTC) for injection into the service, enabling deterministic time control in tests.
- **Key contents:**
  - `@Configuration`
  - `@Bean public Clock clock() { return Clock.systemUTC(); }`

---

### Tests

#### `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
- **Purpose:** Unit tests for all service-layer logic.
- **Key contents:** See §7 below.

#### `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`
- **Purpose:** Slice tests (`@WebMvcTest`) for controller layer.
- **Key contents:** See §7 below.

#### `src/test/resources/application.yml`
- **Purpose:** Override config for tests (H2 in-memory DB, fixed base URL).
- **Key contents:**
  ```yaml
  spring:
    datasource:
      url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
      driver-class-name: org.h2.Driver
    jpa:
      hibernate:
        ddl-auto: create-drop
    flyway:
      enabled: false        # Hibernate creates schema in tests
  app:
    base-url: http://localhost:8080
  ```

---

## 3. Files to Modify

There are no existing source files to modify. This is a greenfield project. The only existing files are:

| File | Current State | Action |
|------|---------------|--------|
| `README.md` | Empty (1 line) | Optionally update with setup/run instructions after implementation |
| `artifacts/prd.md` | PRD document | No changes needed |
| `docs/prd.md` | Summary/redirect note | No changes needed |

---

## 4. Database Schema Changes

### New Table: `short_urls`

#### `src/main/resources/db/migration/V1__create_short_urls.sql`

```sql
CREATE TABLE short_urls (
    id          BIGSERIAL                   PRIMARY KEY,
    code        VARCHAR(6)                  NOT NULL,
    original_url TEXT                       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE   NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE   NOT NULL,
    CONSTRAINT uq_short_urls_code UNIQUE (code)
);

CREATE INDEX idx_short_urls_code     ON short_urls (code);
CREATE INDEX idx_short_urls_expires  ON short_urls (expires_at);
```

**Notes:**
- `BIGSERIAL` auto-increments `id` using a PostgreSQL sequence; maps to `GenerationType.IDENTITY` in JPA.
- `UNIQUE` constraint on `code` is the final safety net against collisions (the service also retries in application code).
- `idx_short_urls_expires` supports future cleanup queries filtering on `expires_at`.
- No explicit `idx_short_urls_code` beyond the implicit unique index is strictly required; the unique constraint creates an index automatically in PostgreSQL, but it is listed explicitly for clarity.

---

## 5. API Changes

This is a new service; all endpoints are net-new.

### `POST /shorten`

**Request:**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?query=param"
}
```

**Success Response — 200 OK:**
```json
{
  "code": "aB3xYz",
  "shortUrl": "http://short.example.com/aB3xYz"
}
```

**Error Responses:**

| Status | Condition | Body |
|--------|-----------|------|
| 400 | `url` missing, blank, or not http/https | `{"error": "url: must match a valid HTTP/HTTPS URL"}` |
| 500 | Code generation exhausted (5 retries) | `{"error": "Internal server error"}` |
| 503 | Database unavailable | `{"error": "Service temporarily unavailable"}` |

---

### `GET /{code}`

**Request:**
```
GET /aB3xYz
```

**Success Response — 302 Found:**
```
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path?query=param
```

**Error Responses:**

| Status | Condition | Body |
|--------|-----------|------|
| 404 | Code not found in DB (or invalid format) | `{"error": "Short code not found: aB3xYz"}` |
| 410 | Code found but expired | `{"error": "Short code has expired: aB3xYz"}` |
| 503 | Database unavailable | `{"error": "Service temporarily unavailable"}` |

---

### Actuator Endpoints (read-only, for ops)

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Liveness/readiness probe; includes DB connectivity |
| `GET /actuator/info` | Application version/build info |

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Initialize Maven project structure
- Create `pom.xml` at repo root with Spring Boot 3.3.x parent, all required dependencies (web, data-jpa, actuator, validation, postgresql, flyway, h2 test scope).
- Create standard Maven directory tree: `src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`.
- Create `UrlShortenerApplication.java` as the `@SpringBootApplication` entry point.
- **Files affected:** `pom.xml`, `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
- **Dependencies:** None — this is the first task.

### Task 2 — Write Flyway migration
- Create `src/main/resources/db/migration/V1__create_short_urls.sql` with the `short_urls` DDL (see §4).
- **Files affected:** `src/main/resources/db/migration/V1__create_short_urls.sql`
- **Dependencies:** Task 1 (directory structure must exist).

### Task 3 — Write `application.yml`
- Create `src/main/resources/application.yml` with datasource, JPA, Flyway, Actuator, logging, and `app.base-url` configuration (see §2).
- **Files affected:** `src/main/resources/application.yml`
- **Dependencies:** Task 1.

### Task 4 — Implement `AppProperties` and `ClockConfig`
- Create `AppProperties.java` — `@ConfigurationProperties(prefix = "app")` with `baseUrl` field.
- Create `ClockConfig.java` — `@Bean Clock clock()` returning `Clock.systemUTC()`.
- Enable properties binding in `UrlShortenerApplication.java` by adding `@ConfigurationPropertiesScan`.
- **Files affected:** `AppProperties.java`, `ClockConfig.java`, `UrlShortenerApplication.java`
- **Dependencies:** Task 1.

### Task 5 — Implement `ShortUrl` entity
- Create `ShortUrl.java` JPA entity (see §2 for field details).
- Use `OffsetDateTime` for `createdAt`/`expiresAt` to ensure timezone-awareness.
- **Files affected:** `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
- **Dependencies:** Task 2 (schema must align with entity).

### Task 6 — Implement `ShortUrlRepository`
- Create `ShortUrlRepository.java` extending `JpaRepository<ShortUrl, Long>` with `findByCode(String code)`.
- **Files affected:** `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`
- **Dependencies:** Task 5.

### Task 7 — Implement DTOs and custom exceptions
- Create `ShortenRequest.java`, `ShortenResponse.java`, `ErrorResponse.java`.
- Create `CodeNotFoundException.java`, `CodeExpiredException.java`, `CodeGenerationException.java`.
- **Files affected:** All DTO and exception classes listed in §2.
- **Dependencies:** Task 1.

### Task 8 — Implement `UrlShortenerService`
- Create `UrlShortenerService.java` with `generateCode()`, `shorten()`, `resolve()` methods (see §2 for full logic).
- Inject `ShortUrlRepository`, `AppProperties`, `Clock`.
- **Files affected:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
- **Dependencies:** Tasks 4, 5, 6, 7.

### Task 9 — Implement `UrlShortenerController`
- Create `UrlShortenerController.java` with `POST /shorten` and `GET /{code}` (see §2).
- **Files affected:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
- **Dependencies:** Tasks 7, 8.

### Task 10 — Implement `GlobalExceptionHandler`
- Create `GlobalExceptionHandler.java` with all `@ExceptionHandler` methods (see §2).
- **Files affected:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
- **Dependencies:** Tasks 7, 9.

### Task 11 — Write unit tests for `UrlShortenerService`
- Create `UrlShortenerServiceTest.java` (see §7 for full test case list).
- Use Mockito to mock `ShortUrlRepository` and inject a fixed `Clock`.
- **Files affected:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
- **Dependencies:** Task 8.

### Task 12 — Write controller slice tests
- Create `UrlShortenerControllerTest.java` using `@WebMvcTest` (see §7).
- Mock `UrlShortenerService` with `@MockBean`.
- **Files affected:** `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`
- **Dependencies:** Tasks 9, 10.

### Task 13 — Write test `application.yml`
- Create `src/test/resources/application.yml` with H2 config and `flyway.enabled=false` (Hibernate creates schema).
- **Files affected:** `src/test/resources/application.yml`
- **Dependencies:** Task 3.

### Task 14 — Verify build and tests pass
- Run `./mvnw clean test` (or `mvn clean test`).
- Fix any compilation or test failures.
- **Files affected:** Any files with errors.
- **Dependencies:** All previous tasks.

---

## 7. Testing Strategy

### Unit Tests — `UrlShortenerServiceTest`

All tests inject a `Mockito.mock(ShortUrlRepository.class)` and a `Clock` fixed via `Clock.fixed(Instant.parse(...), ZoneOffset.UTC)`.

| Test | Description |
|------|-------------|
| `generateCode_returnsExactlySixChars` | Assert `generateCode()` output length is 6 |
| `generateCode_returnsAlphanumericOnly` | Assert output matches `[A-Za-z0-9]{6}` |
| `generateCode_producesDistinctValues` | Generate 1,000 codes; assert at least 990 are distinct (statistical uniqueness) |
| `shorten_persistsEntityWithCorrectFields` | Mock `save()` to return entity; assert `code.length()==6`, `createdAt` is set, `expiresAt == createdAt + 30 days` |
| `shorten_returnsCorrectShortUrl` | Assert `shortUrl == baseUrl + "/" + code` |
| `shorten_retriesOnCollision` | Make `save()` throw `DataIntegrityViolationException` twice then succeed; assert successful response returned |
| `shorten_throwsAfterFiveCollisions` | Make `save()` always throw `DataIntegrityViolationException`; assert `CodeGenerationException` thrown after 5 attempts |
| `resolve_returnsOriginalUrl_whenCodeValidAndNotExpired` | Mock `findByCode()` to return entity with `expiresAt` 1 day in future; assert original URL returned |
| `resolve_throwsCodeNotFoundException_whenCodeNotInDB` | Mock `findByCode()` to return empty; assert `CodeNotFoundException` thrown |
| `resolve_throwsCodeExpiredException_whenExpired` | Mock `findByCode()` to return entity with `expiresAt` 1 day in past; assert `CodeExpiredException` thrown |
| `resolve_throwsCodeNotFoundException_forInvalidCodeFormat` | Call `resolve("!@#$%^")` (invalid chars); assert `CodeNotFoundException` thrown before DB call |
| `resolve_throwsCodeNotFoundException_forShortCode` | Call `resolve("abc")` (only 3 chars); assert `CodeNotFoundException` without DB lookup |
| `resolve_notExpired_exactBoundary` | Set clock to exactly `expiresAt`; assert 410 is thrown (boundary: `isBefore` is strict) |

### Controller Slice Tests — `UrlShortenerControllerTest`

Uses `@WebMvcTest(UrlShortenerController.class)` with `@MockBean UrlShortenerService`.

| Test | Description |
|------|-------------|
| `postShorten_returns200_withValidUrl` | POST valid URL; assert 200, `code` 6 chars, `shortUrl` present |
| `postShorten_returns400_whenUrlMissing` | POST `{}` body; assert 400 with `error` field |
| `postShorten_returns400_whenUrlBlank` | POST `{"url":""}` ; assert 400 |
| `postShorten_returns400_whenUrlNotHttpScheme` | POST `{"url":"ftp://example.com"}`; assert 400 |
| `postShorten_returns400_whenUrlIsJavascriptScheme` | POST `{"url":"javascript:alert(1)"}`; assert 400 |
| `getCode_returns302_withLocationHeader` | Mock service returns URL; assert 302 and `Location` header |
| `getCode_returns404_whenNotFound` | Mock service throws `CodeNotFoundException`; assert 404 + error body |
| `getCode_returns410_whenExpired` | Mock service throws `CodeExpiredException`; assert 410 + error body |
| `getCode_returns503_whenDbUnavailable` | Mock service throws `DataAccessException`; assert 503 + error body |

### Edge Cases to Cover

- Code with exactly 6 chars that matches boundary expiry time (strictly expired vs. active).
- DB save succeeds on the 5th (final) retry attempt.
- `url` field with embedded whitespace or trailing spaces (expect 400).
- `code` path parameter containing path-traversal characters (e.g., `../etc`); expect 404.
- Response body of `GET /{code}` 302 redirect has no body (only `Location` header).
- Error responses always have `Content-Type: application/json`.

---

## 8. Risk Assessment

### Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Short code collision exhausting 5 retries | Low initially; increases with namespace saturation | `POST /shorten` returns 500 | DB-level unique constraint is the final guard; monitor collision rate; upgrade to 8-char codes at >10% saturation |
| `expiresAt` computed with wrong timezone | Low | Incorrect expiry (codes expire too early or too late) | Use `OffsetDateTime.now(Clock.systemUTC())` throughout; inject `Clock` bean for testability |
| `shortUrl` built with wrong host behind proxy | Medium | Broken short URLs in production | Require `app.base-url` to be explicitly configured per environment; validate at startup with `@NotBlank` |
| Hibernate schema validation fails (`ddl-auto: validate`) | Low | Application fails to start | Run Flyway before app starts (default Spring Boot behavior); ensure entity field types match DDL exactly |
| `DataIntegrityViolationException` on non-collision constraints | Low | Swallowed by retry loop, service returns 500 after 5 retries | Log the exception at WARN level on each retry to aid diagnosis |

### Dependency Risks

| Risk | Mitigation |
|------|-----------|
| Flyway version incompatibility with PostgreSQL 15+ | Pin `flyway-core` and `flyway-database-postgresql` to the same version; test against PostgreSQL 15 locally |
| Spring Boot 3.x requires Java 17+ | Use Java 21 LTS; document in README |
| H2 SQL dialect differs from PostgreSQL in tests | Keep H2 only for unit/slice tests; run integration tests against a real PostgreSQL (e.g., Testcontainers in a follow-on) |

### Rollback Plan

Since this is a greenfield service with no prior deployment, rollback is simply not deploying the new service. If the service has been deployed:

1. Stop the Spring Boot application process / remove the container.
2. The `short_urls` table can be dropped if the deployment is to be fully undone:
   ```sql
   DROP TABLE IF EXISTS short_urls;
   ```
3. Re-deploy the previous artifact (none in v1; rollback = no service).

For schema-only rollback, a Flyway undo migration (`U1__drop_short_urls.sql`) can be added:
```sql
DROP TABLE IF EXISTS short_urls;
```

---

## Appendix: Full Package Structure

```
url-shortener/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/urlshortener/
│   │   │   ├── UrlShortenerApplication.java
│   │   │   ├── config/
│   │   │   │   ├── AppProperties.java
│   │   │   │   └── ClockConfig.java
│   │   │   ├── controller/
│   │   │   │   └── UrlShortenerController.java
│   │   │   ├── dto/
│   │   │   │   ├── ShortenRequest.java
│   │   │   │   ├── ShortenResponse.java
│   │   │   │   └── ErrorResponse.java
│   │   │   ├── entity/
│   │   │   │   └── ShortUrl.java
│   │   │   ├── exception/
│   │   │   │   ├── CodeNotFoundException.java
│   │   │   │   ├── CodeExpiredException.java
│   │   │   │   ├── CodeGenerationException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── repository/
│   │   │   │   └── ShortUrlRepository.java
│   │   │   └── service/
│   │   │       └── UrlShortenerService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__create_short_urls.sql
│   └── test/
│       ├── java/com/example/urlshortener/
│       │   ├── service/
│       │   │   └── UrlShortenerServiceTest.java
│       │   └── controller/
│       │       └── UrlShortenerControllerTest.java
│       └── resources/
│           └── application.yml
└── artifacts/
    ├── prd.md
    └── plan.md
```
