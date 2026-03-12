# Implementation Plan: URL Shortener Service

**Generated:** 2026-03-12
**Branch:** main
**PRD Reference:** docs/prd.md (v1.0)

---

## 1. Architecture Overview

This is a **greenfield** Spring Boot 3.x project. No existing source files exist in the repository. The service is a stateless REST API backed by PostgreSQL, deployed as a single runnable JAR.

### Component Diagram

```
 Client / Browser
       │
       │  POST /shorten   GET /{code}
       ▼
┌─────────────────────────────────┐
│       Spring MVC Layer          │
│  UrlShortenerController         │
│  GlobalExceptionHandler         │
└────────────┬────────────────────┘
             │ calls
             ▼
┌─────────────────────────────────┐
│       Service Layer             │
│  UrlShortenerService            │
│  CodeGeneratorService           │
└────────────┬────────────────────┘
             │ JPA
             ▼
┌─────────────────────────────────┐
│       Repository Layer          │
│  UrlMappingRepository           │
│  (Spring Data JPA)              │
└────────────┬────────────────────┘
             │ JDBC / HikariCP
             ▼
┌─────────────────────────────────┐
│          PostgreSQL             │
│  Table: url_mappings            │
│  Flyway schema migration        │
└─────────────────────────────────┘
```

### Request Flow — POST /shorten
1. Controller validates request body (`url` not blank).
2. `UrlShortenerService.shorten()` delegates to `CodeGeneratorService.generate()`.
3. `CodeGeneratorService` uses `SecureRandom` to produce a 6-char `[A-Za-z0-9]` code.
4. Service persists a new `UrlMapping` entity with `createdAt = now()`, `expiresAt = now() + 30 days`.
5. On `DataIntegrityViolationException` (UNIQUE collision), retry up to 5 times, log WARN each retry.
6. Controller builds the `shortUrl` from `HttpServletRequest` host/port and returns 200.

### Request Flow — GET /{code}
1. Controller calls `UrlShortenerService.resolve(code)`.
2. Service queries by code; if absent → throws `CodeNotFoundException` → 404.
3. If present but `expiresAt` is before `now()` → throws `CodeExpiredException` → 410.
4. Otherwise, returns the `originalUrl`; controller replies 302 with `Location` header.

---

## 2. Files to Create

All paths are relative to the repository root. The base package is `com.example.urlshortener`.

### Build & Configuration

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build descriptor; Spring Boot 3.x parent, dependencies, JaCoCo plugin |
| `src/main/resources/application.properties` | DataSource URL (from env), JPA settings, server port |
| `src/main/resources/db/migration/V1__create_url_mappings.sql` | Flyway migration: creates `url_mappings` table + index |
| `docker-compose.yml` | Local dev: PostgreSQL 15 container with health-check |
| `.github/workflows/ci.yml` | GitHub Actions CI: build, test, JaCoCo report |

### Application Entry Point

| File | Purpose |
|------|---------|
| `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` | `@SpringBootApplication` main class |

### Model / Entity

| File | Purpose | Key Contents |
|------|---------|--------------|
| `src/main/java/com/example/urlshortener/model/UrlMapping.java` | JPA entity mapping to `url_mappings` table | Fields: `id` (Long), `code` (String), `originalUrl` (String), `createdAt` (OffsetDateTime), `expiresAt` (OffsetDateTime); `@Column(unique=true)` on `code` |

### DTOs

| File | Purpose | Key Contents |
|------|---------|--------------|
| `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` | Request body for POST /shorten | Field: `url` (String) with `@NotBlank` validation |
| `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` | Response body for POST /shorten | Fields: `code` (String), `shortUrl` (String) |
| `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` | Uniform error body | Field: `error` (String) |

### Repository

| File | Purpose | Key Contents |
|------|---------|--------------|
| `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java` | Spring Data JPA repository | `Optional<UrlMapping> findByCode(String code)`, `boolean existsByCode(String code)` |

### Services

| File | Purpose | Key Contents |
|------|---------|--------------|
| `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java` | Generates random 6-char alphanumeric codes | `String generate()` using `SecureRandom` over `[A-Za-z0-9]` alphabet; stateless, `@Service` |
| `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` | Core business logic | `ShortenResponse shorten(String url, String baseUrl)`, `String resolve(String code)`; handles collision retry (max 5), expiry evaluation |

### Exceptions

| File | Purpose |
|------|---------|
| `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java` | Thrown when code is absent from DB → maps to 404 |
| `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java` | Thrown when code exists but `expiresAt` is past → maps to 410 |
| `src/main/java/com/example/urlshortener/exception/MaxRetriesExceededException.java` | Thrown when collision retry limit exceeded → maps to 500 |

### Controller & Exception Handler

| File | Purpose | Key Contents |
|------|---------|--------------|
| `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` | REST endpoints | `POST /shorten` (validates with `@Valid`), `GET /{code}` (returns `ResponseEntity` with 302 + Location header); extracts base URL from `HttpServletRequest` |
| `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` | Handles `MethodArgumentNotValidException` → 400, `CodeNotFoundException` → 404, `CodeExpiredException` → 410, `MaxRetriesExceededException` → 500, generic `Exception` → 500; suppresses stack traces in response body |

### Tests

| File | Purpose |
|------|---------|
| `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java` | Unit tests for code generation logic |
| `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java` | Unit tests for shorten/resolve logic (Mockito mocks) |
| `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java` | `@WebMvcTest` slice tests for HTTP layer |
| `src/test/java/com/example/urlshortener/repository/UrlMappingRepositoryTest.java` | `@DataJpaTest` slice for repository queries (H2 or Testcontainers) |

---

## 3. Files to Modify

This is a greenfield repository. There are **no existing source files to modify**. The only pre-existing files are:

| File | Action |
|------|--------|
| `README.md` | Append setup/run instructions after implementation |

---

## 4. Database Schema Changes

### New Table: `url_mappings`

```sql
-- src/main/resources/db/migration/V1__create_url_mappings.sql

CREATE TABLE url_mappings (
    id          BIGSERIAL       PRIMARY KEY,
    code        VARCHAR(6)      NOT NULL,
    original_url TEXT           NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL,
    expires_at  TIMESTAMPTZ     NOT NULL,
    CONSTRAINT uq_url_mappings_code UNIQUE (code)
);

CREATE INDEX idx_url_mappings_code ON url_mappings (code);
```

**Notes:**
- `BIGSERIAL` provides auto-incrementing surrogate PK.
- `UNIQUE` constraint on `code` enforces FR-4 at the database level; application retries on `DataIntegrityViolationException`.
- Separate `CREATE INDEX` is explicit even though the UNIQUE constraint creates an implicit index — keeping both makes intent clear and allows future partial-index changes.
- `TIMESTAMPTZ` stores timezone-aware timestamps; application always writes UTC values (FR-6, NFR-2 clock skew mitigation).
- No `expires_at` index needed for v1 (expiry is only checked per-code lookup, not scanned in bulk).

### Flyway Configuration

Add to `application.properties`:
```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

---

## 5. API Changes

This is a new service; all endpoints are net-new.

### POST /shorten

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=true"
}
```

**Responses**

| Status | Body | Condition |
|--------|------|-----------|
| 200 OK | `{ "code": "aB3xZ9", "shortUrl": "http://<host>:<port>/aB3xZ9" }` | Success |
| 400 Bad Request | `{ "error": "url must not be blank" }` | Missing/blank `url` field |
| 500 Internal Server Error | `{ "error": "Failed to generate a unique code" }` | 5 collision retries exhausted |
| 503 Service Unavailable | `{ "error": "Service temporarily unavailable" }` | DB unreachable |

**`shortUrl` construction:** Extract scheme + host + port from the incoming `HttpServletRequest` at runtime (satisfies FR-8 — no hardcoded host).

---

### GET /{code}

**Responses**

| Status | Header / Body | Condition |
|--------|--------------|-----------|
| 302 Found | `Location: <originalUrl>` | Valid, non-expired code |
| 404 Not Found | `{ "error": "Short URL not found" }` | Code not in DB |
| 410 Gone | `{ "error": "Short URL has expired" }` | `expires_at` is in the past |

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Initialize Maven Project Structure

**Files affected:** `pom.xml`, directory skeleton

**Steps:**
1. Create `pom.xml` with:
   - Parent: `spring-boot-starter-parent` 3.2.x (latest stable)
   - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `postgresql` (runtime), `flyway-core`, `flyway-database-postgresql`
   - Test dependencies: `spring-boot-starter-test` (includes JUnit 5, Mockito, AssertJ), `h2` (test scope for `@DataJpaTest`)
   - Plugin: `spring-boot-maven-plugin`, `jacoco-maven-plugin` (minimum line coverage 0.80 on `service` and `controller` packages)
2. Create directory tree: `src/main/java/com/example/urlshortener/{controller,service,repository,model,dto,exception}` and `src/main/resources/db/migration` and `src/test/java/com/example/urlshortener/{service,controller,repository}`

**Depends on:** nothing

---

### Task 2 — Database Migration Script

**Files affected:** `src/main/resources/db/migration/V1__create_url_mappings.sql`

**Steps:**
1. Write migration SQL as shown in Section 4.
2. Verify column types, constraints, and index.

**Depends on:** Task 1

---

### Task 3 — Application Configuration

**Files affected:** `src/main/resources/application.properties`

**Content:**
```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/urlshortener}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}
spring.datasource.hikari.maximum-pool-size=10
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
server.port=${PORT:8080}
```

**Notes:**
- `ddl-auto=validate` ensures Flyway owns schema; Hibernate only validates.
- Credentials from env vars (NFR-6).
- `open-in-view=false` prevents lazy-load anti-pattern in web layer.

**Depends on:** Task 1

---

### Task 4 — JPA Entity

**Files affected:** `src/main/java/com/example/urlshortener/model/UrlMapping.java`

**Key implementation details:**
```java
@Entity
@Table(name = "url_mappings")
public class UrlMapping {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String code;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    // constructors, getters
}
```

**Depends on:** Task 2

---

### Task 5 — DTOs

**Files affected:** `ShortenRequest.java`, `ShortenResponse.java`, `ErrorResponse.java`

**ShortenRequest:**
```java
public record ShortenRequest(@NotBlank(message = "url must not be blank") String url) {}
```

**ShortenResponse:**
```java
public record ShortenResponse(String code, String shortUrl) {}
```

**ErrorResponse:**
```java
public record ErrorResponse(String error) {}
```

Use Java records (Java 16+, available on Java 17) for immutability and brevity.

**Depends on:** Task 1

---

### Task 6 — Repository

**Files affected:** `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java`

```java
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByCode(String code);
    boolean existsByCode(String code);
}
```

**Depends on:** Task 4

---

### Task 7 — CodeGeneratorService

**Files affected:** `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java`

```java
@Service
public class CodeGeneratorService {
    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
```

**Depends on:** Task 1

---

### Task 8 — Custom Exceptions

**Files affected:** `CodeNotFoundException.java`, `CodeExpiredException.java`, `MaxRetriesExceededException.java`

All extend `RuntimeException`. No checked exceptions — Spring MVC's exception handler catches them.

```java
public class CodeNotFoundException extends RuntimeException {
    public CodeNotFoundException(String code) {
        super("Short URL not found: " + code);
    }
}

public class CodeExpiredException extends RuntimeException {
    public CodeExpiredException(String code) {
        super("Short URL has expired: " + code);
    }
}

public class MaxRetriesExceededException extends RuntimeException {
    public MaxRetriesExceededException() {
        super("Failed to generate a unique short code after maximum retries");
    }
}
```

**Depends on:** Task 1

---

### Task 9 — UrlShortenerService

**Files affected:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`

```java
@Service
@Transactional
public class UrlShortenerService {

    private static final int MAX_RETRIES = 5;
    private static final int EXPIRY_DAYS = 30;

    private final UrlMappingRepository repository;
    private final CodeGeneratorService codeGenerator;

    public UrlShortenerService(UrlMappingRepository repository,
                               CodeGeneratorService codeGenerator) { ... }

    public ShortenResponse shorten(String url, String baseUrl) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = codeGenerator.generate();
            try {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                UrlMapping mapping = new UrlMapping(code, url, now, now.plusDays(EXPIRY_DAYS));
                repository.save(mapping);
                return new ShortenResponse(code, baseUrl + "/" + code);
            } catch (DataIntegrityViolationException e) {
                log.warn("Code collision on attempt {}: code={}", attempt + 1, code);
            }
        }
        throw new MaxRetriesExceededException();
    }

    @Transactional(readOnly = true)
    public String resolve(String code) {
        UrlMapping mapping = repository.findByCode(code)
            .orElseThrow(() -> new CodeNotFoundException(code));
        if (mapping.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new CodeExpiredException(code);
        }
        return mapping.getOriginalUrl();
    }
}
```

**Notes:**
- `OffsetDateTime.now(ZoneOffset.UTC)` ensures UTC (NFR clock skew mitigation).
- `@Transactional` wraps each save; retry is outside the transaction to allow a fresh transaction attempt on collision.
- The retry loop catches `DataIntegrityViolationException` from the UNIQUE constraint violation.

**Depends on:** Tasks 6, 7, 8

---

### Task 10 — GlobalExceptionHandler

**Files affected:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .findFirst().orElse("Validation error");
        return new ErrorResponse(message);
    }

    @ExceptionHandler(CodeNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(CodeNotFoundException ex) {
        return new ErrorResponse("Short URL not found");
    }

    @ExceptionHandler(CodeExpiredException.class)
    @ResponseStatus(HttpStatus.GONE)
    public ErrorResponse handleExpired(CodeExpiredException ex) {
        return new ErrorResponse("Short URL has expired");
    }

    @ExceptionHandler(MaxRetriesExceededException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleMaxRetries(MaxRetriesExceededException ex) {
        log.error("Max retries exceeded for code generation", ex);
        return new ErrorResponse("Failed to generate a unique code");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return new ErrorResponse("Internal server error");
    }
}
```

**Notes:**
- No stack traces in response body (NFR-7).
- `DataAccessException` (e.g., DB down) falls through to generic handler → 500; to return 503 instead, add an explicit `@ExceptionHandler(DataAccessException.class)` mapped to `HttpStatus.SERVICE_UNAVAILABLE`.

**Depends on:** Tasks 5, 8

---

### Task 11 — UrlShortenerController

**Files affected:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`

```java
@RestController
public class UrlShortenerController {

    private final UrlShortenerService service;

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(
            @RequestBody @Valid ShortenRequest request,
            HttpServletRequest httpRequest) {
        String baseUrl = httpRequest.getScheme() + "://" + httpRequest.getServerName()
            + (httpRequest.getServerPort() != 80 && httpRequest.getServerPort() != 443
               ? ":" + httpRequest.getServerPort() : "");
        ShortenResponse response = service.shorten(request.url(), baseUrl);
        return ResponseEntity.ok(response);
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

**Notes:**
- `@Valid` triggers Bean Validation on `ShortenRequest`; `GlobalExceptionHandler` catches the resulting `MethodArgumentNotValidException`.
- Base URL is extracted at request time satisfying FR-8.
- `/{code}` path variable naturally handles any 6-char string; service validates existence/expiry.

**Depends on:** Tasks 5, 9, 10

---

### Task 12 — Application Entry Point

**Files affected:** `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`

```java
@SpringBootApplication
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

**Depends on:** Tasks 3, 11

---

### Task 13 — Docker Compose (Local Dev)

**Files affected:** `docker-compose.yml`

```yaml
services:
  db:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: urlshortener
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_URL: jdbc:postgresql://db:5432/urlshortener
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
    depends_on:
      db:
        condition: service_healthy
```

**Depends on:** Tasks 3, 12

---

### Task 14 — Unit Tests: CodeGeneratorService

**Files affected:** `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java`

**Test cases:**
- `generatedCode_isExactlySixCharacters()`
- `generatedCode_containsOnlyAlphanumericCharacters()`
- `generate_producesUniqueCodesAcrossMultipleCalls()` — generate 10,000 codes, assert no duplicates (probabilistic sanity check)
- `generate_distributionIsUniform()` — optional statistical check

**Depends on:** Task 7

---

### Task 15 — Unit Tests: UrlShortenerService

**Files affected:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

**Setup:** `@ExtendWith(MockitoExtension.class)`, mock `UrlMappingRepository` and `CodeGeneratorService`.

**Test cases:**

| Test | Description |
|------|-------------|
| `shorten_savesEntityAndReturnsCode()` | Happy path: mock generator returns "abc123", repo saves, response has correct code and shortUrl |
| `shorten_retriesOnCollision_succeedsOnSecondAttempt()` | First `save()` throws `DataIntegrityViolationException`, second succeeds |
| `shorten_throwsMaxRetriesExceeded_afterFiveFailures()` | All 5 `save()` calls throw `DataIntegrityViolationException`; expect `MaxRetriesExceededException` |
| `resolve_returnsOriginalUrl_forValidCode()` | Repo returns entity with `expiresAt` in future; returns `originalUrl` |
| `resolve_throwsCodeNotFoundException_whenCodeAbsent()` | Repo returns empty `Optional`; expect `CodeNotFoundException` |
| `resolve_throwsCodeExpiredException_whenExpired()` | Entity `expiresAt` is in the past; expect `CodeExpiredException` |
| `resolve_atExactExpiryBoundary_throwsCodeExpiredException()` | `expiresAt = now()` (boundary: already past); expect `CodeExpiredException` |
| `resolve_oneSecondBeforeExpiry_returnsUrl()` | `expiresAt = now().plusSeconds(1)` — valid |
| `shorten_shortUrlContainsBaseUrl()` | Verify `shortUrl = baseUrl + "/" + code` format |

**Depends on:** Task 9

---

### Task 16 — Controller Tests: UrlShortenerController

**Files affected:** `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`

**Setup:** `@WebMvcTest(UrlShortenerController.class)`, mock `UrlShortenerService` with `@MockBean`.

**Test cases:**

| Test | HTTP | Expected |
|------|------|----------|
| `postShorten_validUrl_returns200WithCodeAndShortUrl()` | POST /shorten `{"url":"https://example.com"}` | 200, JSON with `code` and `shortUrl` fields |
| `postShorten_blankUrl_returns400WithError()` | POST /shorten `{"url":""}` | 400, `{"error":"url must not be blank"}` |
| `postShorten_missingUrlField_returns400()` | POST /shorten `{}` | 400 |
| `getCode_validCode_returns302WithLocation()` | GET /abc123 | 302, `Location` header = original URL |
| `getCode_unknownCode_returns404()` | GET /xxxxxx (service throws `CodeNotFoundException`) | 404, `{"error":"Short URL not found"}` |
| `getCode_expiredCode_returns410()` | GET /xxxxxx (service throws `CodeExpiredException`) | 410, `{"error":"Short URL has expired"}` |

**Depends on:** Tasks 10, 11

---

### Task 17 — Repository Tests: UrlMappingRepository

**Files affected:** `src/test/java/com/example/urlshortener/repository/UrlMappingRepositoryTest.java`

**Setup:** `@DataJpaTest` with H2 in-memory (or Testcontainers PostgreSQL for fidelity).

**Test cases:**
- `findByCode_returnsEntity_whenCodeExists()`
- `findByCode_returnsEmpty_whenCodeAbsent()`
- `save_throwsException_onDuplicateCode()` — insert two entities with same code, expect `DataIntegrityViolationException`
- `existsByCode_returnsFalse_whenAbsent()`
- `existsByCode_returnsTrue_whenPresent()`

**Note:** If using H2, add `spring.jpa.database-platform=org.hibernate.dialect.H2Dialect` in `src/test/resources/application.properties`. For full PostgreSQL fidelity, use Testcontainers (`testcontainers-bom`, `postgresql` module).

**Depends on:** Task 6

---

### Task 18 — CI Pipeline

**Files affected:** `.github/workflows/ci.yml`

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: urlshortener
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: postgres
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build and test
        run: ./mvnw verify
        env:
          DB_URL: jdbc:postgresql://localhost:5432/urlshortener
          DB_USERNAME: postgres
          DB_PASSWORD: postgres
      - name: Upload JaCoCo report
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: target/site/jacoco/
```

**Depends on:** Tasks 12–17

---

## 7. Testing Strategy

### Unit Test Cases (JUnit 5 + Mockito)

**CodeGeneratorService** — 3 tests
- Output is exactly 6 characters.
- Output contains only `[A-Za-z0-9]` characters.
- 10,000 generated codes contain no duplicates (birthday-paradox sanity check).

**UrlShortenerService** — 9 tests (see Task 15 above)

**GlobalExceptionHandler** — covered implicitly by `@WebMvcTest` tests for each error path.

### Integration Tests (`@WebMvcTest` / `@DataJpaTest`)

**UrlShortenerControllerTest** — 6 tests (see Task 16 above)

**UrlMappingRepositoryTest** — 5 tests (see Task 17 above)

### Edge Cases to Cover

| Edge Case | Test Location | Notes |
|-----------|--------------|-------|
| Expiry exactly at boundary | `UrlShortenerServiceTest` | `expiresAt == now()` → expired |
| 1 second before expiry | `UrlShortenerServiceTest` | `expiresAt = now() + 1s` → valid |
| Collision retry succeeds on attempt 2 | `UrlShortenerServiceTest` | First save throws, second succeeds |
| All 5 retry attempts fail | `UrlShortenerServiceTest` | `MaxRetriesExceededException` |
| Blank URL string (`""`) | `UrlShortenerControllerTest` | 400 with validation message |
| Null URL field | `UrlShortenerControllerTest` | 400 |
| Missing JSON field entirely | `UrlShortenerControllerTest` | 400 |
| Duplicate code in DB | `UrlMappingRepositoryTest` | `DataIntegrityViolationException` |
| Unknown 6-char code | `UrlShortenerControllerTest` | 404 |

### JaCoCo Coverage Gate

Configure in `pom.xml` to fail build if line coverage on `service` package < 80%:
```xml
<rule>
  <element>PACKAGE</element>
  <limits>
    <limit>
      <counter>LINE</counter>
      <value>COVEREDRATIO</value>
      <minimum>0.80</minimum>
    </limit>
  </limits>
  <includes>
    <include>com/example/urlshortener/service/*</include>
    <include>com/example/urlshortener/controller/*</include>
  </includes>
</rule>
```

---

## 8. Risk Assessment

### Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| **Code collision** — same 6-char code generated twice | Low (1 in ~57B per draw) | Medium | UNIQUE DB constraint; retry up to 5 times; log WARN per retry (NFR-10, NFR-15) |
| **Clock skew** — app and DB clocks differ | Low | Low | Use `OffsetDateTime.now(ZoneOffset.UTC)` in app; expiry comparison is purely against stored `expires_at` value, not a DB-side query — consistent since both `now()` and `expires_at` use the same app clock |
| **DB unreachable** — PostgreSQL down at startup or runtime | Medium | High | `spring.datasource.hikari.*` connection validation; `@ExceptionHandler(DataAccessException.class)` → 503; Docker Compose `depends_on` with health-check |
| **Infinite retry loop** — unbounded retries on collision | None (bounded) | High | MAX_RETRIES = 5; throws `MaxRetriesExceededException` → 500 after exhaustion |
| **Open redirect abuse** — any URL accepted | Medium | Medium | Add scheme allowlist validation (`http`, `https` only) in service layer; reject `ftp://`, `javascript:`, etc. This is minimal and within scope of FR-7 / NFR-5 |
| **URL length unbounded** — very long `originalUrl` input | Low | Low | PostgreSQL `TEXT` is unlimited; add `@Size(max=2048)` on `ShortenRequest.url` |
| **H2 vs PostgreSQL dialect differences** — `@DataJpaTest` uses H2 | Low | Low | Use `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers for full fidelity; or document the H2 limitation |

### Dependency Risks

| Dependency | Risk | Mitigation |
|------------|------|-----------|
| Spring Boot 3.x | Requires Java 17+; breaking changes from Boot 2.x | Pin to a specific 3.2.x version in `pom.xml`; avoid snapshot versions |
| Flyway | Migration history must stay in sync across environments | Never edit `V1__` after it is applied; add new migrations as `V2__`, `V3__`, etc. |
| PostgreSQL JDBC driver | Version mismatch with PostgreSQL server | Use Spring Boot's managed version; test against PostgreSQL 15 |

### Rollback Plan

Since this is a greenfield service (no existing functionality to regress):
1. **Schema rollback:** Drop the `url_mappings` table and delete the Flyway `flyway_schema_history` row.
2. **Application rollback:** Revert to the prior commit or remove the deployment. No data migration is needed since the service holds no state outside PostgreSQL.
3. **Flyway undo:** If `flyway-core` `undo` migration is required, add `V1.1__undo_create_url_mappings.sql` (requires Flyway Teams for `undo`); alternatively, use a plain `DROP TABLE` script executed manually.

---

## Appendix: Implementation Order Summary

```
Task  1  — pom.xml + directory structure
Task  2  — Flyway migration SQL
Task  3  — application.properties
Task  4  — UrlMapping entity
Task  5  — DTOs (ShortenRequest, ShortenResponse, ErrorResponse)
Task  6  — UrlMappingRepository
Task  7  — CodeGeneratorService
Task  8  — Custom exceptions
Task  9  — UrlShortenerService
Task 10  — GlobalExceptionHandler
Task 11  — UrlShortenerController
Task 12  — UrlShortenerApplication (main class)
Task 13  — docker-compose.yml
Task 14  — CodeGeneratorServiceTest
Task 15  — UrlShortenerServiceTest
Task 16  — UrlShortenerControllerTest
Task 17  — UrlMappingRepositoryTest
Task 18  — GitHub Actions CI workflow
```

Total files to create: **~24** (excluding `.github/` subdirectory structure).
Estimated test count: **23+ unit/integration tests**.
