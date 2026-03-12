# Implementation Plan: URL Shortener Service

**Based on:** PRD v1.0 (2026-03-12)
**Target Branch:** main
**Java:** 21 (LTS) | **Framework:** Spring Boot 3.x | **DB:** PostgreSQL | **Build:** Maven

---

## 1. Architecture Overview

This is a greenfield, single-responsibility microservice. No existing code to integrate with.

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Clients / Browsers                   │
└──────────────────────┬──────────────────────────────────────┘
                       │  POST /shorten  /  GET /{code}
┌──────────────────────▼──────────────────────────────────────┐
│              UrlShortenerController  (@RestController)       │
│  • Validates input (Bean Validation + pattern check)         │
│  • Delegates to service layer                                │
│  • Builds shortUrl from configured base-url                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│              UrlShortenerService  (@Service)                 │
│  • Orchestrates: generate code → persist → return           │
│  • Collision retry loop (up to 5 attempts)                   │
│  • Expiry check at read time                                 │
└────────────┬──────────────────────┬─────────────────────────┘
             │                      │
┌────────────▼──────────┐  ┌────────▼────────────────────────┐
│  CodeGeneratorService │  │  ShortUrlRepository              │
│  • Random 6-char code │  │  (Spring Data JPA)               │
│  • [A-Za-z0-9] chars  │  │  • findByCode(code)              │
└───────────────────────┘  │  • existsByCode(code)            │
                           └────────────┬────────────────────┘
                                        │
                           ┌────────────▼────────────────────┐
                           │   PostgreSQL  (short_urls table) │
                           │   id, code, original_url,        │
                           │   created_at, expires_at         │
                           └─────────────────────────────────┘
```

**Key design decisions:**
- `Clock` bean injected into the service layer — enables deterministic unit testing.
- `app.base-url` is a required configuration property with no default — fails fast at startup if unset.
- Flyway manages schema migrations — idempotent and version-controlled.
- Global `@ControllerAdvice` handles all error shapes (404, 410, 400, 500).
- Optimistic insert + catch `DataIntegrityViolationException` for collision handling (the DB UNIQUE constraint is the authoritative guard).

---

## 2. Files to Create

### Build & Configuration

#### `pom.xml`
Root Maven descriptor.
**Key dependencies:**
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `postgresql` (runtime)
- `flyway-core` + `flyway-database-postgresql`
- `spring-boot-starter-test` (test scope — includes JUnit 5 + Mockito)
- `spring-boot-testcontainers` + `testcontainers:postgresql` (integration test scope)
- `jacoco-maven-plugin` (>= 90% coverage gate on service classes)

#### `src/main/resources/application.properties`
```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/urlshortener}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASS:postgres}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
app.base-url=${APP_BASE_URL}        # Required — no default. Fails fast if unset.
app.code-length=6
app.code-max-retries=5
app.expiry-days=30
```

#### `src/main/resources/db/migration/V1__create_short_urls_table.sql`
Flyway migration script (see Section 4).

---

### Source Code

#### `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
Spring Boot entry point (`@SpringBootApplication`).

#### `src/main/java/com/example/urlshortener/config/AppProperties.java`
`@ConfigurationProperties(prefix = "app")` bean:
- `baseUrl: String` — validated `@NotBlank` at startup.
- `codeLength: int` (default 6)
- `codeMaxRetries: int` (default 5)
- `expiryDays: long` (default 30)

#### `src/main/java/com/example/urlshortener/config/ClockConfig.java`
`@Configuration` that exposes a `Clock` bean (`Clock.systemUTC()`).
Allows tests to inject a fixed clock via `@Primary` override.

---

#### `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
Record / class with:
- `@NotBlank String url`
- Custom `@ValidUrl` constraint (or use `@Pattern` + `@URL` from Hibernate Validator) ensuring only `http://` or `https://` schemes are accepted.

#### `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`
Record:
- `String code`
- `String shortUrl`

#### `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`
Record:
- `int status`
- `String error`
- `String message`

---

#### `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
JPA entity mapped to `short_urls`:
```java
@Entity @Table(name = "short_urls")
public class ShortUrl {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String code;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "created_at", nullable = false,
            columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false,
            columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;
}
```

---

#### `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`
```java
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByCode(String code);
    boolean existsByCode(String code);
}
```

---

#### `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java`
**Purpose:** Generate random 6-character `[A-Za-z0-9]` codes.
**Key method:**
```java
public String generate();  // returns a random 6-char alphanumeric string
```
**Implementation:** Use `SecureRandom` with a static `ALPHABET` of all 62 chars. Loop `codeLength` times, pick a random index.

---

#### `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
**Purpose:** Core business logic.
**Key methods:**
```java
// Creates a new short URL mapping. Retries on collision (up to maxRetries).
// Throws CodeGenerationException after exhausting retries.
public ShortUrl shorten(String originalUrl);

// Looks up a code. Returns the ShortUrl if valid.
// Throws CodeNotFoundException (404) or CodeExpiredException (410).
public ShortUrl resolve(String code);
```
**`shorten` logic:**
1. Loop up to `maxRetries` times:
   a. Call `codeGeneratorService.generate()`.
   b. If `repository.existsByCode(code)` → retry.
   c. Otherwise build `ShortUrl` with `createdAt = clock.instant()`, `expiresAt = createdAt + expiryDays`.
   d. Try `repository.save(entity)`.
   e. Catch `DataIntegrityViolationException` → retry (concurrent collision guard).
2. If all retries exhausted → throw `CodeGenerationException` → mapped to HTTP 500.

**`resolve` logic:**
1. `repository.findByCode(code)` → empty → throw `CodeNotFoundException`.
2. If `entity.expiresAt.isBefore(clock.instant())` → throw `CodeExpiredException`.
3. Return entity.

---

#### `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java`
`RuntimeException` carrying the requested code.

#### `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java`
`RuntimeException` carrying the requested code.

#### `src/main/java/com/example/urlshortener/exception/CodeGenerationException.java`
`RuntimeException` — all retry attempts exhausted.

#### `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
`@RestControllerAdvice` mapping:
| Exception | HTTP Status | `error` field |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | "Bad Request" |
| `ConstraintViolationException` | 400 | "Bad Request" |
| `CodeNotFoundException` | 404 | "Not Found" |
| `CodeExpiredException` | 410 | "Gone" |
| `CodeGenerationException` | 500 | "Internal Server Error" |
| Catch-all `Exception` | 500 | "Internal Server Error" (no stack trace in body) |

Each handler returns `ErrorResponse` JSON.

---

#### `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
```java
@RestController
public class UrlShortenerController {

    // POST /shorten
    // Validates request body (@Valid), calls service.shorten(),
    // builds shortUrl from appProperties.getBaseUrl() + "/" + code,
    // returns ShortenResponse with HTTP 200.
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(
        @Valid @RequestBody ShortenRequest request);

    // GET /{code}
    // Validates code matches [A-Za-z0-9]{6} via @Pattern,
    // calls service.resolve(), returns HTTP 302 with Location header.
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
        @PathVariable @Pattern(regexp = "[A-Za-z0-9]{6}") String code);
}
```
The `@Pattern` on `{code}` triggers `ConstraintViolationException` → 400 for malformed codes, before any DB access (NFR-07).

---

### Test Code

#### `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java`
Unit tests (JUnit 5, no mocks needed):
- Code length is exactly 6.
- All characters are within `[A-Za-z0-9]`.
- Generate 10,000 codes, verify no consecutive duplicates (statistical uniqueness).

#### `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
Unit tests (JUnit 5 + Mockito, fixed `Clock`):
- `shorten()` persists entity with correct `expiresAt = createdAt + 30 days`.
- `shorten()` retries on `existsByCode` returning true, succeeds on second attempt.
- `shorten()` retries on `DataIntegrityViolationException`, succeeds on second attempt.
- `shorten()` throws `CodeGenerationException` after max retries all fail.
- `resolve()` throws `CodeNotFoundException` when repository returns empty.
- `resolve()` throws `CodeExpiredException` when `expiresAt` is in the past.
- `resolve()` returns entity when `expiresAt` is in the future.
- `resolve()` throws `CodeExpiredException` when `expiresAt` == `now` (boundary: equal is expired).

#### `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`
Slice tests (`@WebMvcTest` + Mockito):
- `POST /shorten` with valid URL → 200 + JSON body.
- `POST /shorten` with missing `url` field → 400.
- `POST /shorten` with `javascript:alert(1)` URL → 400.
- `POST /shorten` with `data:text/html,...` URL → 400.
- `GET /abc123` (valid, not expired) → 302 + `Location` header.
- `GET /abc123` (not found) → 404 + error JSON.
- `GET /abc123` (expired) → 410 + error JSON.
- `GET /abc12!` (invalid char) → 400.
- `GET /abc12` (5 chars) → 400.

#### `src/test/java/com/example/urlshortener/integration/UrlShortenerIntegrationTest.java`
`@SpringBootTest` + Testcontainers PostgreSQL:
- Full round-trip: POST /shorten → GET /{code} → 302.
- POST same URL twice → two different codes returned.
- GET with unknown code → 404.
- GET with code whose `expires_at` is manually set to the past → 410.

#### `src/test/resources/application-test.properties`
Overrides datasource to point to Testcontainers; sets `app.base-url=http://localhost:8080`.

---

## 3. Files to Modify

**None.** This is a greenfield project. The only existing files are `README.md` (empty) and documentation; neither requires modification for the implementation.

---

## 4. Database Schema Changes

### New Table: `short_urls`

```sql
-- src/main/resources/db/migration/V1__create_short_urls_table.sql

CREATE TABLE short_urls (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(6)   NOT NULL,
    original_url TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_short_urls_code UNIQUE (code)
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
```

**Notes:**
- `BIGSERIAL` — auto-incrementing surrogate PK; not exposed in the API.
- `VARCHAR(6)` with UNIQUE constraint enforced at DB level (FR-16, NFR-13).
- `TIMESTAMPTZ` stores timestamps with time zone; all values written in UTC (FR-18).
- The index on `code` satisfies FR-17 and supports O(1) lookup.
- Flyway names the file `V1__...` so it runs once and is idempotent on re-deploy.

---

## 5. API Changes

This is a new service. All endpoints are new.

### `POST /shorten`

**Request:**
```http
POST /shorten HTTP/1.1
Content-Type: application/json

{ "url": "https://www.example.com/some/very/long/path?query=value" }
```

**Success Response (HTTP 200):**
```json
{
  "code": "aB3xZ9",
  "shortUrl": "http://localhost:8080/aB3xZ9"
}
```

**Error Responses:**
| Condition | Status | `message` example |
|---|---|---|
| Missing or blank `url` | 400 | "url must not be blank" |
| Invalid URL scheme | 400 | "url must be a valid http or https URL" |
| Code generation failed | 500 | "Failed to generate a unique short code after 5 attempts." |

---

### `GET /{code}`

**Request:**
```http
GET /aB3xZ9 HTTP/1.1
```

**Success Response (HTTP 302):**
```http
HTTP/1.1 302 Found
Location: https://www.example.com/some/very/long/path?query=value
```

**Error Responses:**
| Condition | Status | `message` example |
|---|---|---|
| Code not in DB | 404 | "Short code 'aB3xZ9' does not exist." |
| Code expired | 410 | "Short code 'aB3xZ9' has expired." |
| Code has invalid chars/length | 400 | "redirect.code: must match \"[A-Za-z0-9]{6}\"" |

---

### Error Response Schema (all errors)
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Short code 'aB3xZ9' does not exist."
}
```

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Initialize Maven Project Structure
**Files affected:** `pom.xml`, `src/` directory tree
**Dependencies:** None

Create the Maven project skeleton:
1. Create `pom.xml` with `spring-boot-starter-parent` as parent (Spring Boot 3.3.x), `groupId=com.example`, `artifactId=url-shortener`, `java.version=21`.
2. Add all dependencies listed in Section 2.
3. Configure `maven-surefire-plugin` (unit tests) and `maven-failsafe-plugin` (integration tests with `*IT.java` naming).
4. Add `jacoco-maven-plugin` with a `check` goal enforcing 90% line coverage on `com.example.urlshortener.service.*`.
5. Create the `src/main/java/com/example/urlshortener/` and `src/test/` directory structure.
6. Create `UrlShortenerApplication.java` with `@SpringBootApplication` and `main()`.

**Verification:** `mvn clean compile` succeeds.

---

### Task 2 — Database Migration Script
**Files affected:** `src/main/resources/db/migration/V1__create_short_urls_table.sql`
**Dependencies:** Task 1

Write the Flyway migration exactly as shown in Section 4. No Java code changes needed.

---

### Task 3 — Application Configuration
**Files affected:** `src/main/resources/application.properties`, `AppProperties.java`, `ClockConfig.java`
**Dependencies:** Task 1

1. Create `application.properties` as shown in Section 2. Leave `app.base-url` with no default.
2. Create `AppProperties.java` as a `@ConfigurationProperties(prefix = "app")` class. Annotate the `baseUrl` field `@NotBlank` and enable validation with `@Validated`.
3. Add `@EnableConfigurationProperties(AppProperties.class)` to the main application class.
4. Create `ClockConfig.java` exposing `Clock.systemUTC()` as a `@Bean`.
5. Create `src/test/resources/application-test.properties` with `app.base-url=http://localhost:8080` and `spring.flyway.enabled=false` (integration tests use Testcontainers which will run migrations).

**Verification:** Application starts and fails fast if `APP_BASE_URL` is not set.

---

### Task 4 — JPA Entity and Repository
**Files affected:** `ShortUrl.java`, `ShortUrlRepository.java`
**Dependencies:** Tasks 2, 3

1. Create `ShortUrl.java` with JPA annotations as shown in Section 2.
2. Create `ShortUrlRepository.java` extending `JpaRepository<ShortUrl, Long>` with `findByCode` and `existsByCode`.

**Verification:** `mvn clean compile` succeeds. Entity matches the Flyway schema.

---

### Task 5 — DTOs and Error Types
**Files affected:** `ShortenRequest.java`, `ShortenResponse.java`, `ErrorResponse.java`, `CodeNotFoundException.java`, `CodeExpiredException.java`, `CodeGenerationException.java`
**Dependencies:** Task 1

1. Create `ShortenRequest.java`. Use `@NotBlank` on `url`. Add a custom `@ValidUrl` annotation (or use Hibernate's `@URL(regexp = "https?://.*")`) that also enforces only `http`/`https` schemes per NFR-04.
   - Recommended: use `@Pattern(regexp = "https?://.+")` combined with `@NotBlank` for simplicity and no extra dependencies.
2. Create `ShortenResponse.java` and `ErrorResponse.java` as simple records.
3. Create the three exception classes extending `RuntimeException`, each storing the relevant context (e.g., the `code` string).

---

### Task 6 — Code Generator Service
**Files affected:** `CodeGeneratorService.java`
**Dependencies:** Tasks 3, 5

```java
@Service
public class CodeGeneratorService {
    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();
    private final int codeLength;

    public CodeGeneratorService(AppProperties props) {
        this.codeLength = props.getCodeLength();
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
```

**Verification:** Unit test `CodeGeneratorServiceTest` passes (Task 9).

---

### Task 7 — URL Shortener Service
**Files affected:** `UrlShortenerService.java`
**Dependencies:** Tasks 4, 5, 6

Implement `shorten()` and `resolve()` exactly as described in Section 2. Key points:
- Inject `Clock clock` (not `LocalDateTime.now()` directly).
- Use `clock.instant()` for `createdAt`; `createdAt.plus(expiryDays, ChronoUnit.DAYS)` for `expiresAt`.
- The retry loop catches both `existsByCode == true` and `DataIntegrityViolationException`.
- `resolve()` uses `expiresAt.isBefore(clock.instant()) || expiresAt.equals(clock.instant())` — treat boundary as expired (FR-22).
  Simplified: `!expiresAt.isAfter(clock.instant())`.

**Verification:** Unit test `UrlShortenerServiceTest` passes (Task 9).

---

### Task 8 — Controller and Global Exception Handler
**Files affected:** `UrlShortenerController.java`, `GlobalExceptionHandler.java`
**Dependencies:** Tasks 5, 7

1. Implement `UrlShortenerController` as shown in Section 2. Use `UriComponentsBuilder` or simple string concatenation with `appProperties.getBaseUrl()` + `"/" + code` for `shortUrl` construction.
2. Implement `GlobalExceptionHandler` with all mappings from Section 2. Ensure no stack trace fields are included in `ErrorResponse`.
3. Enable `@Validated` on the controller class so `@Pattern` on `@PathVariable` is enforced.

**Verification:** Controller slice tests pass (Task 9).

---

### Task 9 — Unit Tests
**Files affected:** `CodeGeneratorServiceTest.java`, `UrlShortenerServiceTest.java`, `UrlShortenerControllerTest.java`
**Dependencies:** Tasks 6, 7, 8

Write all unit tests described in Section 7. Use `Clock.fixed(instant, ZoneOffset.UTC)` for time-sensitive assertions.
Run: `mvn test`

---

### Task 10 — Integration Tests
**Files affected:** `UrlShortenerIntegrationTest.java`, `src/test/resources/application-test.properties`
**Dependencies:** Tasks 2–9

1. Add Testcontainers PostgreSQL dependency (already declared in `pom.xml` from Task 1).
2. Annotate test class with `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers`.
3. Declare a `@Container static PostgreSQLContainer<?>` — Spring Boot 3.1+ auto-configures the datasource from the container.
4. Write all integration scenarios from Section 7.
5. For the expiry scenario, insert a row directly via repository with `expiresAt = Instant.now().minus(1, DAYS)` to simulate an expired link.

Run: `mvn verify`

---

### Task 11 — CI/CD Configuration
**Files affected:** `.github/workflows/ci.yml`
**Dependencies:** Tasks 1–10

Create a GitHub Actions workflow:
```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: mvn verify
        # 'verify' runs unit tests, integration tests, and JaCoCo coverage check
```
Testcontainers will pull the PostgreSQL Docker image during the `verify` phase on the CI runner.

---

## 7. Testing Strategy

### Unit Tests (fast, no DB)

**`CodeGeneratorServiceTest`**
| # | Test case | Assertion |
|---|---|---|
| 1 | Single generated code has length 6 | `assertEquals(6, code.length())` |
| 2 | All chars in generated code are alphanumeric | `assertTrue(code.matches("[A-Za-z0-9]{6}"))` |
| 3 | 10,000 codes contain uppercase, lowercase, and digits | at least one of each character class seen |
| 4 | Collision rate: 10,000 codes, count distinct, expect > 9,900 | statistical uniqueness |

**`UrlShortenerServiceTest`** (Mockito mocks for `ShortUrlRepository` and `CodeGeneratorService`, fixed `Clock`)
| # | Test case | Assertion |
|---|---|---|
| 1 | Happy path shorten → entity saved with correct `expiresAt` = `createdAt + 30d` | `verify(repository).save(...)` + argument captor |
| 2 | `existsByCode` returns true on first call, false on second → shorten succeeds after one retry | `verify(codeGen, times(2)).generate()` |
| 3 | `DataIntegrityViolationException` thrown on first save, second save succeeds | retry path taken |
| 4 | All 5 retries fail → `CodeGenerationException` thrown | `assertThrows(CodeGenerationException.class, ...)` |
| 5 | `resolve()` with unknown code → `CodeNotFoundException` | `assertThrows(...)` |
| 6 | `resolve()` with `expiresAt` 1 second in the past → `CodeExpiredException` | `assertThrows(...)` |
| 7 | `resolve()` with `expiresAt` exactly equal to `now` → `CodeExpiredException` (boundary) | `assertThrows(...)` |
| 8 | `resolve()` with `expiresAt` 1 second in the future → returns entity | success |

**`UrlShortenerControllerTest`** (`@WebMvcTest`, Mockito for `UrlShortenerService`)
| # | Test case | Expected HTTP |
|---|---|---|
| 1 | `POST /shorten` with `{"url":"https://example.com"}` | 200 + JSON body |
| 2 | `POST /shorten` with `{}` | 400 |
| 3 | `POST /shorten` with `{"url":""}` | 400 |
| 4 | `POST /shorten` with `{"url":"javascript:alert(1)"}` | 400 |
| 5 | `POST /shorten` with `{"url":"data:text/html,<h1>hi</h1>"}` | 400 |
| 6 | `POST /shorten` with `{"url":"ftp://files.example.com"}` | 400 |
| 7 | `GET /aB3xZ9` → service returns valid entity | 302 + `Location` header |
| 8 | `GET /aB3xZ9` → service throws `CodeNotFoundException` | 404 + error JSON |
| 9 | `GET /aB3xZ9` → service throws `CodeExpiredException` | 410 + error JSON |
| 10 | `GET /abc12!` (invalid char) | 400 |
| 11 | `GET /abc12` (only 5 chars) | 400 |
| 12 | `GET /abc1234` (7 chars) | 400 |

### Integration Tests (Testcontainers PostgreSQL)

| # | Scenario |
|---|---|
| 1 | `POST /shorten` → `GET /{code}` full round-trip returns 302 with correct `Location` |
| 2 | `POST /shorten` with the same URL twice → two different codes in the response |
| 3 | `GET` with a code that was never inserted → 404 |
| 4 | `GET` with a code whose `expires_at` is set to yesterday → 410 |
| 5 | `POST /shorten` response `shortUrl` starts with configured `app.base-url` |

### Edge Cases
- URL containing query string (`?a=1&b=2`) — preserved verbatim in `original_url` and `Location` header.
- Very long URL (> 2,000 characters) — accepted; `TEXT` column has no length limit.
- URL with unicode in path (`/résumé`) — accepted; stored as-is.
- Code lookup is case-sensitive: `ABC123` ≠ `abc123`.
- `expires_at` exactly equal to the current instant → 410 (not 302).

---

## 8. Risk Assessment

### Risk 1 — Short Code Collision Under High Concurrency
**Likelihood:** Low (56.8B combinations)
**Impact:** Medium — one request fails without retry
**Mitigation (implemented):**
- Retry loop up to 5 attempts in `UrlShortenerService.shorten()`.
- DB UNIQUE constraint + catch `DataIntegrityViolationException` as final guard.
- Unit tests verify retry path (Tasks 9.2 and 9.3).

**Rollback:** If collisions become frequent at scale, pre-generate a code pool (out of scope for v1).

---

### Risk 2 — SSRF / Malicious URL Storage
**Likelihood:** Medium (open, unauthenticated API)
**Impact:** High
**Mitigation (implemented):**
- `@Pattern(regexp = "https?://.+")` on `ShortenRequest.url` blocks `javascript:`, `data:`, `ftp:`, etc.
- Service never fetches the submitted URL.
- Unit tests verify all rejected schemes (Task 9: tests 4–6).

**Rollback:** Tighten the regex or add an IP-range blocklist in a subsequent PR.

---

### Risk 3 — Clock Skew in Multi-Instance Deployments
**Likelihood:** Low
**Impact:** Medium (inconsistent expiry near boundary)
**Mitigation (implemented):**
- All timestamps written in UTC via `Clock.systemUTC()`.
- Injected `Clock` bean enables tests to use a fixed instant — no flakiness.
- Database `TIMESTAMPTZ` type is timezone-aware.

**Rollback:** Switch `createdAt`/`expiresAt` to use `NOW()` from the DB server for a single time source (requires a native query or DB trigger, deferred to v2).

---

### Risk 4 — Database Unavailability
**Likelihood:** Low
**Impact:** High
**Mitigation (implemented):**
- Spring Boot Actuator `/actuator/health` exposes liveness/readiness.
- HikariCP connection pool with default timeout settings.
- Global exception handler returns 500 (not an unhandled JVM crash) for unexpected DB errors.

**Rollback:** No data loss risk — stateless service. Simply restart when DB is restored.

---

### Risk 5 — `shortUrl` Host Misconfiguration
**Likelihood:** Medium (deployment oversight)
**Impact:** Low-Medium (wrong host in response)
**Mitigation (implemented):**
- `app.base-url` has no default value in `application.properties`.
- `@NotBlank` + `@Validated` on `AppProperties` causes startup failure with a clear error if the property is missing.
- Integration test sets `app.base-url=http://localhost:8080` explicitly.

**Rollback:** Set the correct `APP_BASE_URL` environment variable and restart. No data migration needed.

---

### Risk 6 — Flyway Migration Failure on Re-deploy
**Likelihood:** Low
**Impact:** Medium (service fails to start)
**Mitigation:** Migration script uses `IF NOT EXISTS` style is not needed — Flyway's checksum mechanism prevents re-running `V1`. If schema drift occurs, use `flyway:repair`. Staging environment should run `mvn flyway:migrate` before production.

---

## Appendix: Complete File List

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
│   │   │   │   ├── CodeExpiredException.java
│   │   │   │   ├── CodeGenerationException.java
│   │   │   │   ├── CodeNotFoundException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── repository/
│   │   │   │   └── ShortUrlRepository.java
│   │   │   └── service/
│   │   │       ├── CodeGeneratorService.java
│   │   │       └── UrlShortenerService.java
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/
│   │           └── V1__create_short_urls_table.sql
│   └── test/
│       ├── java/com/example/urlshortener/
│       │   ├── controller/
│       │   │   └── UrlShortenerControllerTest.java
│       │   ├── integration/
│       │   │   └── UrlShortenerIntegrationIT.java
│       │   └── service/
│       │       ├── CodeGeneratorServiceTest.java
│       │       └── UrlShortenerServiceTest.java
│       └── resources/
│           └── application-test.properties
└── .github/
    └── workflows/
        └── ci.yml
```

Total new files: **21**
Total modified files: **0** (greenfield)
