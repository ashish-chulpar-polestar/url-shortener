# Implementation Plan: URL Shortener Service

**Generated:** 2026-03-12
**PRD Version:** 1.0
**Branch:** main
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener.git

---

## 1. Architecture Overview

This is a greenfield Spring Boot microservice. The repository contains no source code yet — only documentation. The full project must be scaffolded from scratch.

### Component Diagram

```
  Client
    |
    | HTTP
    v
+---------------------------+
|   UrlShortenerController  |  (Spring Web MVC)
|  POST /shorten            |
|  GET  /{code}             |
+----------+----------------+
           |
           v
+---------------------------+
|     UrlShortenerService   |  (Business logic)
|  - generateCode()         |
|  - shorten(url, request)  |
|  - resolve(code)          |
+----------+----------------+
           |
           v
+---------------------------+
|   UrlMappingRepository    |  (Spring Data JPA)
|  - findByCode(code)       |
+----------+----------------+
           |
           v
+---------------------------+
|       PostgreSQL          |
|   table: url_mappings     |
|  id, code, original_url,  |
|  created_at, expires_at   |
+---------------------------+
```

### Request Flows

**POST /shorten**
1. Controller receives `ShortenRequest` JSON
2. Validates URL is non-blank and starts with `http://` or `https://`
3. Service generates a random 6-char alphanumeric code
4. Service checks uniqueness via repository; retries on collision (max 5 attempts)
5. Service persists `UrlMapping` entity with `expires_at = created_at + 30 days`
6. Controller returns 201 with `ShortenResponse` containing `code` and `shortUrl`

**GET /{code}**
1. Controller receives `code` path variable
2. Service looks up code in repository
3. If not found → 404
4. If found and `expires_at` is past → 410
5. If found and valid → 302 redirect to `original_url`

---

## 2. Files to Create

### Build & Configuration

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build — Spring Boot 3.2.x, Java 17, JPA, PostgreSQL, Flyway, validation, test dependencies |
| `src/main/resources/application.properties` | App config: datasource (env vars), server port, Flyway, base-url property |
| `src/main/resources/db/migration/V1__create_url_mappings.sql` | Flyway DDL to create `url_mappings` table and unique index on `code` |

### Main Source

| File | Purpose |
|------|---------|
| `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` | Spring Boot entry point (`@SpringBootApplication`) |
| `src/main/java/com/example/urlshortener/model/UrlMapping.java` | JPA entity mapping to `url_mappings` table |
| `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` | Request DTO with `url` field, Bean Validation annotations |
| `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` | Response DTO with `code` and `shortUrl` fields |
| `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` | Error DTO with `error` field for uniform 4xx/5xx responses |
| `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java` | Spring Data JPA repository; `findByCode(String code)` |
| `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java` | Stateless service: generates random 6-char alphanumeric codes; injectable `Clock` |
| `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` | Core business logic: shorten, resolve, expiry check; depends on repo + generator |
| `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` | REST controller exposing `POST /shorten` and `GET /{code}` |
| `src/main/java/com/example/urlshortener/exception/ShortCodeNotFoundException.java` | Custom exception → mapped to 404 |
| `src/main/java/com/example/urlshortener/exception/ShortCodeExpiredException.java` | Custom exception → mapped to 410 |
| `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` mapping all exceptions to `ErrorResponse` JSON |
| `src/main/java/com/example/urlshortener/config/AppConfig.java` | `@Bean Clock` (UTC) and `@Value app.base-url` exposure |

### Test Source

| File | Purpose |
|------|---------|
| `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java` | Unit tests: code length, charset, randomness (no mocks needed) |
| `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java` | Unit tests: expiry check (valid/expired/boundary), collision retry, 404 path — mocked repository + fixed Clock |

---

## 3. Files to Modify

There are no existing source files to modify. This is a greenfield project. All files listed in Section 2 are net-new creations.

The only pre-existing files are:
- `README.md` — currently empty; update with project description and run instructions after implementation.
- `artifacts/prd.md` — read-only reference; do not modify.

---

## 4. Database Schema Changes

### New Table: `url_mappings`

```sql
-- src/main/resources/db/migration/V1__create_url_mappings.sql

CREATE TABLE url_mappings (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_url_mappings_code UNIQUE (code)
);

CREATE UNIQUE INDEX idx_url_mappings_code ON url_mappings (code);
```

**Notes:**
- `BIGSERIAL` provides auto-incrementing surrogate PK.
- `VARCHAR(6)` enforces max length at DB level.
- `UNIQUE` constraint + explicit index ensures fast O(log n) lookups and collision detection at DB level.
- `TIMESTAMPTZ` stores timezone-aware timestamps; all application code uses UTC (`Instant` / `ZonedDateTime`).
- No `UNIQUE` constraint on `original_url` — duplicate long URLs intentionally receive different codes (FR-5).

---

## 5. API Changes

This is a new service — all endpoints are new.

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/very/long/path?query=param"
}
```

**Responses**

| Status | Condition | Body |
|--------|-----------|------|
| 201 Created | URL accepted and shortened | `{ "code": "aB3xY9", "shortUrl": "http://localhost:8080/aB3xY9" }` |
| 400 Bad Request | `url` missing, blank, or not http(s) | `{ "error": "url must not be blank" }` |
| 503 Service Unavailable | Database error | `{ "error": "Service temporarily unavailable" }` |

### GET /{code}

**Request**
```
GET /aB3xY9
```

**Responses**

| Status | Condition | Body / Header |
|--------|-----------|---------------|
| 302 Found | Code exists and is not expired | `Location: https://www.example.com/...` |
| 404 Not Found | Code does not exist | `{ "error": "Short code not found" }` |
| 410 Gone | Code exists but expired | `{ "error": "Short URL has expired" }` |
| 503 Service Unavailable | Database error | `{ "error": "Service temporarily unavailable" }` |

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Scaffold Maven project

**Files:** `pom.xml`, `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`

Create `pom.xml` with:
- `parent`: `spring-boot-starter-parent` 3.2.x
- `java.version`: 17
- Dependencies:
  - `spring-boot-starter-web`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-validation`
  - `postgresql` (runtime)
  - `flyway-core`
  - `spring-boot-starter-test` (test scope — includes JUnit 5 + Mockito)
- Plugin: `spring-boot-maven-plugin`

Create the main application class:
```java
@SpringBootApplication
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

**Dependencies:** None (first task).

---

### Task 2 — Application configuration

**Files:** `src/main/resources/application.properties`

```properties
server.port=8080

# DataSource — values supplied via environment variables
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA / Hibernate
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# Application-specific base URL (override in production)
app.base-url=http://localhost:8080
```

**Notes:**
- `ddl-auto=validate` ensures Hibernate validates schema against entities but never mutates it — Flyway owns DDL.
- `app.base-url` is injected into the service to construct `shortUrl` without relying on `HttpServletRequest` (avoids proxy/reverse-proxy hostname issues in production).

**Dependencies:** Task 1.

---

### Task 3 — Database migration script

**Files:** `src/main/resources/db/migration/V1__create_url_mappings.sql`

Content as specified in Section 4.

**Dependencies:** Task 2.

---

### Task 4 — JPA Entity

**Files:** `src/main/java/com/example/urlshortener/model/UrlMapping.java`

```java
@Entity
@Table(name = "url_mappings")
public class UrlMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

**Dependencies:** Task 3.

---

### Task 5 — Repository

**Files:** `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java`

```java
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByCode(String code);
    boolean existsByCode(String code);
}
```

- `findByCode` used during redirect resolution.
- `existsByCode` used during code generation uniqueness check (avoids loading full entity for collision checks).

**Dependencies:** Task 4.

---

### Task 6 — DTOs and custom exceptions

**Files:**
- `dto/ShortenRequest.java`
- `dto/ShortenResponse.java`
- `dto/ErrorResponse.java`
- `exception/ShortCodeNotFoundException.java`
- `exception/ShortCodeExpiredException.java`

`ShortenRequest`:
```java
public class ShortenRequest {
    @NotBlank(message = "url must not be blank")
    @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
    private String url;
    // getter/setter
}
```

`ShortenResponse`:
```java
public record ShortenResponse(String code, String shortUrl) {}
```

`ErrorResponse`:
```java
public record ErrorResponse(String error) {}
```

`ShortCodeNotFoundException` — extends `RuntimeException`.
`ShortCodeExpiredException` — extends `RuntimeException`.

**Dependencies:** Task 1.

---

### Task 7 — Clock bean configuration

**Files:** `src/main/java/com/example/urlshortener/config/AppConfig.java`

```java
@Configuration
public class AppConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

Injecting `Clock` instead of calling `Instant.now()` directly allows tests to supply a fixed clock for deterministic expiry assertions (R-6 mitigation).

**Dependencies:** Task 1.

---

### Task 8 — CodeGeneratorService

**Files:** `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java`

```java
@Service
public class CodeGeneratorService {
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
}
```

- Uses `SecureRandom` for unpredictable codes (FR-3).
- Alphabet is exactly 62 chars (A–Z, a–z, 0–9) per FR-2.
- No external dependencies; straightforward to unit test.

**Dependencies:** Task 1.

---

### Task 9 — UrlShortenerService

**Files:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`

```java
@Service
public class UrlShortenerService {
    private static final int MAX_RETRIES = 5;
    private static final int EXPIRY_DAYS = 30;

    private final UrlMappingRepository repository;
    private final CodeGeneratorService codeGenerator;
    private final Clock clock;

    @Value("${app.base-url}")
    private String baseUrl;

    // constructor injection

    public ShortenResponse shorten(String url) {
        String code = generateUniqueCode();
        Instant now = Instant.now(clock);
        UrlMapping mapping = new UrlMapping();
        mapping.setCode(code);
        mapping.setOriginalUrl(url);
        mapping.setCreatedAt(now);
        mapping.setExpiresAt(now.plus(EXPIRY_DAYS, ChronoUnit.DAYS));
        repository.save(mapping);
        return new ShortenResponse(code, baseUrl + "/" + code);
    }

    public String resolve(String code) {
        UrlMapping mapping = repository.findByCode(code)
            .orElseThrow(ShortCodeNotFoundException::new);
        if (Instant.now(clock).isAfter(mapping.getExpiresAt())) {
            throw new ShortCodeExpiredException();
        }
        return mapping.getOriginalUrl();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = codeGenerator.generate();
            if (!repository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate unique code after " + MAX_RETRIES + " attempts");
    }
}
```

**Dependencies:** Tasks 5, 6, 7, 8.

---

### Task 10 — GlobalExceptionHandler

**Files:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShortCodeNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ShortCodeNotFoundException ex) {
        return new ErrorResponse("Short code not found");
    }

    @ExceptionHandler(ShortCodeExpiredException.class)
    @ResponseStatus(HttpStatus.GONE)
    public ErrorResponse handleExpired(ShortCodeExpiredException ex) {
        return new ErrorResponse("Short URL has expired");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getDefaultMessage())
            .findFirst()
            .orElse("Invalid request");
        return new ErrorResponse(message);
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleDatabase(DataAccessException ex) {
        return new ErrorResponse("Service temporarily unavailable");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleCodeExhaustion(IllegalStateException ex) {
        return new ErrorResponse("Service temporarily unavailable");
    }
}
```

**Dependencies:** Tasks 6, 9.

---

### Task 11 — UrlShortenerController

**Files:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`

```java
@RestController
public class UrlShortenerController {

    private final UrlShortenerService service;

    // constructor injection

    @PostMapping("/shorten")
    @ResponseStatus(HttpStatus.CREATED)
    public ShortenResponse shorten(@Valid @RequestBody ShortenRequest request) {
        return service.shorten(request.getUrl());
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String originalUrl = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(originalUrl))
            .build();
    }
}
```

**Dependencies:** Tasks 6, 9, 10.

---

### Task 12 — Unit tests: CodeGeneratorService

**Files:** `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java`

Test cases (see Section 7 for full list). No mocks required.

**Dependencies:** Task 8.

---

### Task 13 — Unit tests: UrlShortenerService

**Files:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

Test cases using Mockito to mock `UrlMappingRepository` and `CodeGeneratorService`, with `Clock.fixed(...)` for deterministic time. See Section 7.

**Dependencies:** Tasks 9, 12.

---

### Task 14 — Update README

**Files:** `README.md`

Add: project description, prerequisites, environment variable configuration, build & run instructions (`./mvnw spring-boot:run`), test instructions (`./mvnw test`).

**Dependencies:** All prior tasks.

---

## 7. Testing Strategy

### Unit Tests: CodeGeneratorServiceTest

| Test | Assertion |
|------|-----------|
| `generate_returnsExactlySixCharacters` | `code.length() == 6` |
| `generate_containsOnlyAlphanumericCharacters` | `code.matches("[A-Za-z0-9]{6}")` |
| `generate_producesRandomCodes` | Call `generate()` 1000 times; assert at least 990 distinct values (statistical randomness check) |

No Spring context needed — instantiate `CodeGeneratorService` directly.

### Unit Tests: UrlShortenerServiceTest

**Setup:** Mockito mocks for `UrlMappingRepository` and `CodeGeneratorService`; `Clock.fixed(Instant.parse("2026-03-12T12:00:00Z"), ZoneOffset.UTC)`.

| Test | Setup | Assertion |
|------|-------|-----------|
| `shorten_persistsMappingAndReturnsCode` | `existsByCode` returns false; `save` returns entity | Response `code` == generated code; `shortUrl` == `baseUrl + "/" + code`; `save` called once |
| `shorten_retriesOnCollision` | `existsByCode` returns true twice then false | `generate()` called 3 times; `save` called once |
| `shorten_throwsAfterMaxRetries` | `existsByCode` always returns true | `IllegalStateException` thrown |
| `resolve_validCode_returnsOriginalUrl` | `findByCode` returns mapping with `expiresAt` = fixed clock + 1 day | Returns correct `originalUrl` |
| `resolve_expiredCode_throwsShortCodeExpiredException` | `findByCode` returns mapping with `expiresAt` = fixed clock - 1ms | `ShortCodeExpiredException` thrown |
| `resolve_expiredCode_exactBoundary` | `findByCode` returns mapping with `expiresAt` = fixed clock instant (exactly now) | `ShortCodeExpiredException` thrown (expired at boundary, `isAfter` is false but `!isBefore` — implement as `!now.isBefore(expiresAt)`) |
| `resolve_validCode_oneMillisecondBeforeExpiry` | `findByCode` returns mapping with `expiresAt` = fixed clock + 1ms | Returns `originalUrl` (not expired) |
| `resolve_unknownCode_throwsShortCodeNotFoundException` | `findByCode` returns `Optional.empty()` | `ShortCodeNotFoundException` thrown |

**Note on boundary condition:** Use `Instant.now(clock).isBefore(mapping.getExpiresAt())` as the validity check (i.e., expired when `now >= expiresAt`), matching the PRD requirement that expiry is at-or-past `expires_at`.

### Edge Cases to Cover

- `POST /shorten` with missing `url` field → 400
- `POST /shorten` with `url = ""` → 400
- `POST /shorten` with `url = "ftp://example.com"` → 400 (must be http/https)
- `GET /{code}` with a code that has never existed → 404
- `GET /{code}` with a code that is exactly at `expires_at` (boundary) → 410
- Same long URL submitted twice → two independent codes, both persisted
- DB unavailable during `POST /shorten` → 503 with JSON error body
- DB unavailable during `GET /{code}` → 503 with JSON error body

---

## 8. Risk Assessment

### Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| **Code collision under high write volume** (R-1) | Low | Medium | `generateUniqueCode()` retries up to 5 times; throws `IllegalStateException` (→ 503) after exhaustion. Log collision events. |
| **Clock skew on expiry decisions** (R-2) | Low | Low | Use `TIMESTAMPTZ` in PostgreSQL; compare using `Instant.now(clock)` in UTC throughout. Never use wall-clock `LocalDateTime` without zone. |
| **Database SPOF** (R-3) | Medium | High | Catch `DataAccessException` in `GlobalExceptionHandler` → 503 with JSON body. HikariCP connection pooling (Spring Boot default) provides resilience. |
| **Schema drift** (R-4) | Medium | Medium | Flyway manages all DDL from day one. `spring.jpa.hibernate.ddl-auto=validate` prevents Hibernate from silently altering schema. |
| **Malicious URL redirect** (R-5) | Medium | High | Bean Validation `@Pattern(regexp = "https?://.+")` rejects non-http(s) URLs at request time. Service stores string as-is; never follows or executes it. |
| **Test coverage gaps at expiry boundary** (R-6) | Medium | Medium | Explicit boundary tests at `expiresAt = now`, `expiresAt = now - 1ms`, `expiresAt = now + 1ms` using injected fixed `Clock`. |
| **Short URL host misconfiguration** (R-7) | Low | Medium | `app.base-url` property externalizes host. Document required env var for production. Default to `http://localhost:8080` for local dev. |

### Dependency Risks

| Dependency | Risk | Mitigation |
|------------|------|------------|
| Spring Boot 3.2.x | Requires Java 17+; breaking changes from Boot 2.x | Use Spring Initializr defaults; follow migration guide if upgrading |
| Flyway | Migration checksum conflicts if SQL edited after first run | Never edit applied migration files; create new versioned migrations for changes |
| PostgreSQL driver | Version mismatch with PG server | Pin `postgresql` driver version in `pom.xml`; test against PG 13+ |

### Rollback Plan

Since this is a net-new service with no existing users:

1. **Code rollback:** `git revert` or reset branch to pre-implementation commit.
2. **Database rollback:** Drop the `url_mappings` table. With Flyway: `flyway repair` then drop table manually (or add a `V2__drop_url_mappings.sql` undo migration).
3. **Deployment rollback:** Stop/remove the container or process; no state shared with other services.

There are no modifications to existing systems, so rollback risk is minimal.

---

## Appendix: File Tree After Implementation

```
url-shortener/
  pom.xml
  README.md
  artifacts/
    prd.md
    plan.md
  src/
    main/
      java/com/example/urlshortener/
        UrlShortenerApplication.java
        config/
          AppConfig.java
        controller/
          UrlShortenerController.java
        dto/
          ShortenRequest.java
          ShortenResponse.java
          ErrorResponse.java
        exception/
          ShortCodeNotFoundException.java
          ShortCodeExpiredException.java
          GlobalExceptionHandler.java
        model/
          UrlMapping.java
        repository/
          UrlMappingRepository.java
        service/
          CodeGeneratorService.java
          UrlShortenerService.java
      resources/
        application.properties
        db/
          migration/
            V1__create_url_mappings.sql
    test/
      java/com/example/urlshortener/
        service/
          CodeGeneratorServiceTest.java
          UrlShortenerServiceTest.java
```

**Total new files: 18**
**Total modified files: 1** (README.md)
