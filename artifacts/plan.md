# Implementation Plan: URL Shortener Service

**Date:** 2026-03-12
**Branch:** main
**PRD Reference:** docs/prd.md

---

## 1. Architecture Overview

This is a **greenfield Spring Boot 3.x REST API** with no existing application code. The service is stateless, backed by PostgreSQL, and managed via Flyway migrations.

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     HTTP Client / Browser                   │
└──────────────────────┬───────────────────┬──────────────────┘
                       │ POST /shorten      │ GET /{code}
                       ▼                   ▼
┌─────────────────────────────────────────────────────────────┐
│               UrlShortenerController                        │
│  • Input validation (Jakarta Bean Validation + @Valid)      │
│  • Delegates to UrlShortenerService                         │
│  • Returns 201 / 302 / 404 / 410 / 400                     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               UrlShortenerService                           │
│  • Orchestrates shorten / resolve flows                     │
│  • Calls CodeGeneratorService for unique code               │
│  • Handles collision retry (up to 5 attempts)               │
│  • Evaluates expiry using UTC clock                         │
└───────────┬──────────────────────┬──────────────────────────┘
            │                      │
            ▼                      ▼
┌─────────────────────┐  ┌─────────────────────────────────┐
│ CodeGeneratorService│  │     ShortUrlRepository          │
│ • SecureRandom      │  │  • Spring Data JPA              │
│ • 62-char alphabet  │  │  • findByCode(code)             │
│ • 6-char codes      │  │  • existsByCode(code)           │
└─────────────────────┘  └──────────────┬──────────────────┘
                                        │
                                        ▼
                         ┌──────────────────────────────────┐
                         │          PostgreSQL               │
                         │  Table: short_urls               │
                         │  (id, code, original_url,        │
                         │   created_at, expires_at)        │
                         └──────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              GlobalExceptionHandler                         │
│  • @RestControllerAdvice                                    │
│  • Maps domain exceptions → HTTP status + error body       │
│  • Handles DB connectivity failures → 503                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              Flyway (schema migration)                      │
│  • V1__create_short_urls_table.sql                         │
│  • Runs automatically on app startup                       │
└─────────────────────────────────────────────────────────────┘
```

### Package Layout

```
com.example.urlshortener
├── UrlShortenerApplication.java
├── controller/
│   └── UrlShortenerController.java
├── dto/
│   ├── ShortenRequest.java
│   ├── ShortenResponse.java
│   └── ErrorResponse.java
├── entity/
│   └── ShortUrl.java
├── exception/
│   ├── ShortUrlNotFoundException.java
│   ├── ShortUrlExpiredException.java
│   └── GlobalExceptionHandler.java
├── repository/
│   └── ShortUrlRepository.java
└── service/
    ├── CodeGeneratorService.java
    └── UrlShortenerService.java
```

---

## 2. Files to Create

### 2.1 Build Configuration

#### `pom.xml`
- **Purpose:** Maven build descriptor for the Spring Boot project.
- **Key contents:**
  - Parent: `spring-boot-starter-parent` 3.x (latest stable)
  - Java version: 17
  - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `flyway-core`, `postgresql` (runtime), `spring-boot-starter-test`, `h2` (test scope)
  - Plugin: `spring-boot-maven-plugin`

#### `src/main/resources/application.properties`
- **Purpose:** Externalized configuration.
- **Key contents:**
  ```properties
  spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/urlshortener}
  spring.datasource.username=${DB_USERNAME:postgres}
  spring.datasource.password=${DB_PASSWORD:postgres}
  spring.jpa.hibernate.ddl-auto=validate
  spring.flyway.enabled=true
  server.forward-headers-strategy=native
  management.endpoints.web.exposure.include=health
  ```

#### `src/test/resources/application-test.properties`
- **Purpose:** Override datasource for unit/integration tests using H2.
- **Key contents:**
  ```properties
  spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL
  spring.datasource.driver-class-name=org.h2.Driver
  spring.flyway.enabled=true
  ```

### 2.2 Database Migration

#### `src/main/resources/db/migration/V1__create_short_urls_table.sql`
- **Purpose:** Flyway migration to create the `short_urls` table.
- **Key contents:**
  ```sql
  CREATE TABLE short_urls (
      id          BIGSERIAL PRIMARY KEY,
      code        VARCHAR(6)   NOT NULL UNIQUE,
      original_url TEXT        NOT NULL,
      created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
      expires_at  TIMESTAMPTZ  NOT NULL
  );

  CREATE INDEX idx_short_urls_code ON short_urls (code);
  CREATE INDEX idx_short_urls_expires_at ON short_urls (expires_at);
  ```
  The `expires_at` index is added to support future purge queries (Risk 2 mitigation).

### 2.3 Application Entry Point

#### `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
- **Purpose:** Spring Boot main class.
- **Key contents:** `@SpringBootApplication`, `main()` calling `SpringApplication.run(...)`.

### 2.4 Entity

#### `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
- **Purpose:** JPA entity mapping to the `short_urls` table.
- **Key contents:**
  - `@Entity`, `@Table(name = "short_urls")`
  - Fields: `Long id`, `String code`, `String originalUrl`, `Instant createdAt`, `Instant expiresAt`
  - `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` on `id`
  - `@Column(name = "original_url", nullable = false, length = 2048)` on `originalUrl`
  - `@Column(name = "code", nullable = false, unique = true, length = 6)` on `code`
  - `@Column(name = "created_at")`, `@Column(name = "expires_at")`

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

### 2.6 DTOs

#### `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`
- **Purpose:** Request body for `POST /shorten`.
- **Key contents:**
  ```java
  public record ShortenRequest(
      @NotBlank(message = "url must not be blank")
      @Size(max = 2048, message = "url must not exceed 2048 characters")
      @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
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
- **Purpose:** Uniform error response body.
- **Key contents:**
  ```java
  public record ErrorResponse(String error) {}
  ```

### 2.7 Domain Exceptions

#### `src/main/java/com/example/urlshortener/exception/ShortUrlNotFoundException.java`
- **Purpose:** Thrown when a code does not exist in the database.
- **Key contents:** Extends `RuntimeException`. Constructor takes `String code`.

#### `src/main/java/com/example/urlshortener/exception/ShortUrlExpiredException.java`
- **Purpose:** Thrown when a code exists but `expires_at` is in the past.
- **Key contents:** Extends `RuntimeException`. Constructor takes `String code`.

### 2.8 Global Exception Handler

#### `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
- **Purpose:** Translate domain exceptions and validation failures to HTTP responses.
- **Key contents:**
  ```
  @RestControllerAdvice
  Methods:
    handleNotFound(ShortUrlNotFoundException)     → 404 + ErrorResponse
    handleExpired(ShortUrlExpiredException)       → 410 + ErrorResponse
    handleValidation(MethodArgumentNotValidException) → 400 + ErrorResponse
    handleConstraintViolation(ConstraintViolationException) → 400 + ErrorResponse
    handleDataAccessException(DataAccessException) → 503 + ErrorResponse
  ```

### 2.9 Services

#### `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java`
- **Purpose:** Generates cryptographically random 6-character alphanumeric codes.
- **Key contents:**
  ```java
  @Service
  public class CodeGeneratorService {
      private static final String ALPHABET =
          "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"; // 62 chars
      private static final int CODE_LENGTH = 6;
      private final SecureRandom secureRandom = new SecureRandom();

      public String generate() {
          // Builds a 6-char string by randomly sampling ALPHABET
      }
  }
  ```

#### `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
- **Purpose:** Business logic for shortening and resolving URLs.
- **Key contents:**
  ```java
  @Service
  @Transactional
  public class UrlShortenerService {
      private static final int MAX_RETRIES = 5;

      // shorten(String url) → ShortUrl
      //   1. Loop up to MAX_RETRIES:
      //      a. Generate code via CodeGeneratorService
      //      b. If !repository.existsByCode(code): break
      //   2. Build ShortUrl entity (createdAt=now, expiresAt=now+30 days)
      //   3. repository.save(entity)
      //   4. Return saved entity

      // resolve(String code) → ShortUrl
      //   1. repository.findByCode(code)
      //         .orElseThrow(() → ShortUrlNotFoundException)
      //   2. if entity.getExpiresAt().isBefore(Instant.now()):
      //         throw ShortUrlExpiredException
      //   3. return entity
  }
  ```
  - Constructor injection of `ShortUrlRepository` and `CodeGeneratorService`.
  - `shorten()` annotated `@Transactional`; `resolve()` annotated `@Transactional(readOnly = true)`.

### 2.10 Controller

#### `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
- **Purpose:** REST endpoints for the URL shortener.
- **Key contents:**
  ```java
  @RestController
  @Validated
  public class UrlShortenerController {

      // POST /shorten
      @PostMapping("/shorten")
      public ResponseEntity<ShortenResponse> shorten(
          @Valid @RequestBody ShortenRequest request,
          HttpServletRequest httpRequest
      ) {
          ShortUrl saved = service.shorten(request.url());
          String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
              .replacePath("").toUriString();
          String shortUrl = baseUrl + "/" + saved.getCode();
          return ResponseEntity.status(201).body(new ShortenResponse(saved.getCode(), shortUrl));
      }

      // GET /{code}
      @GetMapping("/{code}")
      public ResponseEntity<Void> redirect(@PathVariable String code) {
          ShortUrl entity = service.resolve(code);
          return ResponseEntity.status(302)
              .location(URI.create(entity.getOriginalUrl()))
              .build();
      }
  }
  ```
  - Uses `ServletUriComponentsBuilder` for host-agnostic `shortUrl` construction (Risk 5 mitigation).
  - `server.forward-headers-strategy=native` in `application.properties` ensures `X-Forwarded-*` headers are respected.

### 2.11 Tests

#### `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java`
- **Purpose:** Unit tests for code generation logic.
- **Test cases:**
  - `generatedCode_hasLength6()`
  - `generatedCode_isAlphanumeric()` — matches `[A-Za-z0-9]{6}`
  - `generate_producesUniqueValues()` — generate 1000 codes, assert no duplicates
  - `generatedCode_usesFullAlphabet()` — statistical sanity check over large sample

#### `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
- **Purpose:** Unit tests for shortening and expiry logic (Mockito-based, no DB).
- **Test cases:**
  - `shorten_savesEntityWithCorrectExpiryOf30Days()`
  - `shorten_returnsEntityWithGeneratedCode()`
  - `shorten_retriesOnCollision()` — stub `existsByCode` to return `true` first N times
  - `resolve_throwsNotFoundException_whenCodeMissing()`
  - `resolve_throwsExpiredException_whenExpired()`
  - `resolve_returnsEntity_whenValidAndNotExpired()`
  - `resolve_atExpiryBoundary_isExpired()` — `expiresAt` exactly equals `Instant.now()`

#### `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerIntegrationTest.java`
- **Purpose:** Integration tests using `@SpringBootTest` + H2 (via `application-test.properties`).
- **Annotations:** `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`
- **Test cases:**
  - `postShorten_validUrl_returns201WithCodeAndShortUrl()`
  - `postShorten_sameUrlTwice_returnsDifferentCodes()`
  - `postShorten_blankUrl_returns400()`
  - `postShorten_invalidScheme_returns400()` — e.g., `ftp://...`
  - `postShorten_urlExceeds2048Chars_returns400()`
  - `getCode_validUnexpiredCode_returns302WithLocation()`
  - `getCode_unknownCode_returns404()`
  - `getCode_expiredCode_returns410()` — insert entity with `expires_at` in the past

---

## 3. Files to Modify

This is a greenfield project. The only existing tracked files are:

| File | Current State | Required Change |
|------|--------------|-----------------|
| `README.md` | Empty (1 line) | Add project description, local setup instructions, environment variables reference, and how to run tests. (Low priority — can be done last.) |

No other existing source files require modification.

---

## 4. Database Schema Changes

### New Table: `short_urls`

```sql
CREATE TABLE short_urls (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL UNIQUE,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
CREATE INDEX idx_short_urls_expires_at ON short_urls (expires_at);
```

### Migration Tool
- Flyway is used. The script above becomes `V1__create_short_urls_table.sql` under `src/main/resources/db/migration/`.
- Flyway runs automatically on application startup before the application context finishes initializing.
- On a fresh database, Flyway creates its own `flyway_schema_history` table and applies `V1` exactly once.

### Notes
- `TIMESTAMPTZ` stores timestamps with timezone in PostgreSQL; Java maps this to `Instant`.
- The `UNIQUE` constraint on `code` is the database-level enforcement (FR-17). The application-level `existsByCode` check is a performance optimization to avoid relying solely on exception handling.
- The `idx_short_urls_expires_at` index is included now to support efficient future purge jobs (Risk 2 mitigation) without requiring a subsequent migration.

---

## 5. API Changes

This is a new service with no prior endpoints.

### 5.1 `POST /shorten` — Create Short URL

**Request**
```
POST /shorten
Content-Type: application/json

{
  "url": "https://www.example.com/some/very/long/path?with=query&params=true"
}
```

**Responses**

| Status | Condition | Body |
|--------|-----------|------|
| 201 Created | Valid URL, code generated | `{ "code": "aB3xZ9", "shortUrl": "http://host/aB3xZ9" }` |
| 400 Bad Request | Missing/blank `url` field | `{ "error": "url must not be blank" }` |
| 400 Bad Request | Invalid URL scheme or malformed | `{ "error": "url must start with http:// or https://" }` |
| 400 Bad Request | URL exceeds 2048 characters | `{ "error": "url must not exceed 2048 characters" }` |
| 503 Service Unavailable | Database unavailable | `{ "error": "Service temporarily unavailable" }` |

**Validation rules applied at controller layer (Jakarta Bean Validation):**
- `@NotBlank` — rejects null/empty/whitespace-only strings
- `@Size(max = 2048)` — caps input length (NFR-07)
- `@Pattern(regexp = "https?://.+")` — enforces http/https scheme (NFR-05, Risk 3)

### 5.2 `GET /{code}` — Redirect

**Request**
```
GET /aB3xZ9
```

**Responses**

| Status | Condition | Body |
|--------|-----------|------|
| 302 Found | Valid, non-expired code | No body; `Location: <original_url>` header |
| 404 Not Found | Code does not exist | `{ "error": "Short URL not found" }` |
| 410 Gone | Code exists but expired | `{ "error": "Short URL has expired" }` |

**Notes:**
- `{code}` is case-sensitive (FR-09). Spring MVC path variables preserve case by default.
- No normalisation or trimming is applied to `{code}`.

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Initialise Maven Project Structure
**Files affected:** `pom.xml`, `src/` directory tree
**Dependencies:** None
**Details:**
1. Create `pom.xml` with Spring Boot 3.x parent, Java 17, all required dependencies (web, data-jpa, validation, actuator, flyway-core, postgresql runtime, h2 test, spring-boot-starter-test).
2. Create the standard Maven source directories:
   - `src/main/java/com/example/urlshortener/`
   - `src/main/resources/db/migration/`
   - `src/test/java/com/example/urlshortener/`
   - `src/test/resources/`

---

### Task 2 — Create Flyway Migration Script
**Files affected:** `src/main/resources/db/migration/V1__create_short_urls_table.sql`
**Dependencies:** Task 1 (directory exists)
**Details:**
1. Write the `CREATE TABLE short_urls` DDL with all five columns.
2. Add `UNIQUE` constraint on `code` inline.
3. Create `idx_short_urls_code` index.
4. Create `idx_short_urls_expires_at` index.

---

### Task 3 — Configure Application Properties
**Files affected:** `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
**Dependencies:** Task 1
**Details:**
1. `application.properties`:
   - Datasource via `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` env vars with sane defaults.
   - `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns the schema).
   - `server.forward-headers-strategy=native` for proxy support.
   - Actuator health endpoint enabled.
2. `application-test.properties`:
   - Override datasource to H2 in-memory (PostgreSQL compatibility mode).
   - Keep Flyway enabled so migration runs against H2.

---

### Task 4 — Create Application Entry Point
**Files affected:** `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`
**Dependencies:** Task 1
**Details:**
1. Annotate with `@SpringBootApplication`.
2. `public static void main(String[] args) { SpringApplication.run(UrlShortenerApplication.class, args); }`

---

### Task 5 — Create JPA Entity
**Files affected:** `src/main/java/com/example/urlshortener/entity/ShortUrl.java`
**Dependencies:** Task 2 (schema exists to validate against)
**Details:**
1. Annotate with `@Entity`, `@Table(name = "short_urls")`.
2. Map all five columns with exact column names to match migration.
3. Use `Instant` for `createdAt` and `expiresAt` (UTC, no timezone ambiguity).
4. Use `@GeneratedValue(strategy = GenerationType.IDENTITY)` for `id`.
5. Do NOT add `@PrePersist` for `createdAt` — let the DB `DEFAULT NOW()` handle it, or set it explicitly in the service (choose explicit in service for testability).

---

### Task 6 — Create Repository
**Files affected:** `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`
**Dependencies:** Task 5
**Details:**
1. Extend `JpaRepository<ShortUrl, Long>`.
2. Declare `Optional<ShortUrl> findByCode(String code)`.
3. Declare `boolean existsByCode(String code)`.
Spring Data JPA generates implementations automatically from method names.

---

### Task 7 — Create DTOs
**Files affected:** `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`, `ShortenResponse.java`, `ErrorResponse.java`
**Dependencies:** Task 1
**Details:**
1. Use Java `record` types for immutability and conciseness.
2. Apply Jakarta Bean Validation annotations to `ShortenRequest.url` as specified in Section 2.6.
3. No validation annotations on response DTOs.

---

### Task 8 — Create Domain Exceptions
**Files affected:** `ShortUrlNotFoundException.java`, `ShortUrlExpiredException.java`
**Dependencies:** Task 1
**Details:**
1. Both extend `RuntimeException`.
2. Each takes a `String code` constructor argument.
3. `getMessage()` returns a human-readable string (e.g., `"Short URL not found: " + code`).

---

### Task 9 — Create CodeGeneratorService
**Files affected:** `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java`
**Dependencies:** Task 1
**Details:**
1. Declare `ALPHABET` constant with all 62 alphanumeric characters (A-Z, a-z, 0-9).
2. Declare `CODE_LENGTH = 6` constant.
3. Instantiate `SecureRandom` as a field (thread-safe, expensive to construct).
4. `generate()`: loop `CODE_LENGTH` times, appending `ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length()))` to a `StringBuilder`.

---

### Task 10 — Create UrlShortenerService
**Files affected:** `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`
**Dependencies:** Tasks 6, 8, 9
**Details:**
1. Inject `ShortUrlRepository` and `CodeGeneratorService` via constructor.
2. `shorten(String url)`:
   a. Loop up to `MAX_RETRIES` (5) to generate a unique code.
   b. For each iteration: call `codeGenerator.generate()`, check `repository.existsByCode(code)`.
   c. If unique code found: break loop.
   d. If all retries exhausted: throw `IllegalStateException("Failed to generate unique code after retries")`.
   e. Build `ShortUrl` with `createdAt = Instant.now()`, `expiresAt = createdAt.plus(30, ChronoUnit.DAYS)`.
   f. `repository.save(entity)`.
3. `resolve(String code)`:
   a. `repository.findByCode(code).orElseThrow(() -> new ShortUrlNotFoundException(code))`.
   b. `if (entity.getExpiresAt().isBefore(Instant.now())) throw new ShortUrlExpiredException(code)`.
   c. Return entity.

---

### Task 11 — Create GlobalExceptionHandler
**Files affected:** `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`
**Dependencies:** Tasks 7, 8
**Details:**
1. Annotate with `@RestControllerAdvice`.
2. `@ExceptionHandler(ShortUrlNotFoundException.class)` → `ResponseEntity` with status 404 + `ErrorResponse("Short URL not found")`.
3. `@ExceptionHandler(ShortUrlExpiredException.class)` → 410 + `ErrorResponse("Short URL has expired")`.
4. `@ExceptionHandler(MethodArgumentNotValidException.class)` → 400 + first field error message from binding result.
5. `@ExceptionHandler(ConstraintViolationException.class)` → 400 + first constraint message.
6. `@ExceptionHandler(DataAccessException.class)` → 503 + `ErrorResponse("Service temporarily unavailable")`.
Note: `DataAccessException` is Spring's translation of all JDBC/JPA exceptions, including connection failures (NFR-08).

---

### Task 12 — Create Controller
**Files affected:** `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`
**Dependencies:** Tasks 7, 8, 10
**Details:**
1. Annotate with `@RestController`, `@Validated`.
2. Inject `UrlShortenerService` via constructor.
3. `POST /shorten`:
   - Annotate with `@PostMapping("/shorten")`.
   - Accept `@Valid @RequestBody ShortenRequest`.
   - Accept `HttpServletRequest` to build base URL.
   - Call `service.shorten(request.url())`.
   - Build `shortUrl` using `ServletUriComponentsBuilder.fromRequestUri(httpRequest).replacePath("").toUriString() + "/" + saved.getCode()`.
   - Return `ResponseEntity.status(HttpStatus.CREATED).body(new ShortenResponse(...))`.
4. `GET /{code}`:
   - Annotate with `@GetMapping("/{code}")`.
   - Accept `@PathVariable String code`.
   - Call `service.resolve(code)`.
   - Return `ResponseEntity.status(HttpStatus.FOUND).location(URI.create(entity.getOriginalUrl())).build()`.

---

### Task 13 — Write Unit Tests: CodeGeneratorService
**Files affected:** `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java`
**Dependencies:** Task 9
**Details:** See Section 7 for full test case list. Use JUnit 5 only (no Mockito needed — class has no dependencies).

---

### Task 14 — Write Unit Tests: UrlShortenerService
**Files affected:** `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`
**Dependencies:** Tasks 9, 10
**Details:** Use Mockito to mock `ShortUrlRepository` and `CodeGeneratorService`. See Section 7 for full test case list.

---

### Task 15 — Write Integration Tests: Controller
**Files affected:** `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerIntegrationTest.java`, `src/test/resources/application-test.properties`
**Dependencies:** Tasks 3, 12, 14
**Details:** Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` (or `MockMvc`). `@ActiveProfiles("test")` activates H2 overrides. See Section 7 for full test case list.

---

### Task 16 — Update README
**Files affected:** `README.md`
**Dependencies:** All prior tasks
**Details:**
1. Project description and purpose.
2. Prerequisites: Java 17+, Maven, PostgreSQL (or Docker).
3. Environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
4. How to run locally: `mvn spring-boot:run`.
5. How to run tests: `mvn test`.
6. Docker Compose snippet for local PostgreSQL.
7. API usage examples (curl).

---

## 7. Testing Strategy

### Unit Tests

#### `CodeGeneratorServiceTest`

| Test | Assertion |
|------|-----------|
| `generatedCode_hasLength6` | `code.length() == 6` |
| `generatedCode_isAlphanumeric` | `code.matches("[A-Za-z0-9]{6}")` |
| `generate_producesUniqueValues` | Generate 10,000 codes; `Set.size() == 10,000` |
| `generate_usesUpperAndLowerCase` | Over 1,000 codes, at least one uppercase and one lowercase char appears |
| `generate_usesDigits` | Over 1,000 codes, at least one digit appears |

#### `UrlShortenerServiceTest`

| Test | Setup | Assertion |
|------|-------|-----------|
| `shorten_savesEntityWithExpiryIn30Days` | Stub `existsByCode` → false; stub `save` → entity | `entity.getExpiresAt()` is within 1 second of `now + 30 days` |
| `shorten_returnsEntityWithGeneratedCode` | Stub `existsByCode` → false; `generate` → "abc123" | Returned entity has code `"abc123"` |
| `shorten_retriesOnCollisionAndSucceeds` | `existsByCode` returns true twice, then false; `generate` → "aaa111", "bbb222", "ccc333" | `save` called with code `"ccc333"` |
| `shorten_throwsAfterMaxRetries` | `existsByCode` always returns true | `IllegalStateException` thrown |
| `resolve_throwsNotFoundException` | `findByCode` → `Optional.empty()` | `ShortUrlNotFoundException` thrown |
| `resolve_throwsExpiredException` | `findByCode` → entity with `expiresAt` = 1 day ago | `ShortUrlExpiredException` thrown |
| `resolve_returnsEntity_whenValid` | `findByCode` → entity with `expiresAt` = tomorrow | Entity returned without exception |
| `resolve_atExpiryBoundary_isExpired` | `findByCode` → entity with `expiresAt` = 1 millisecond ago | `ShortUrlExpiredException` thrown |

### Integration Tests

#### `UrlShortenerControllerIntegrationTest` (Spring Boot + H2)

| Test | HTTP Call | Expected Status | Notes |
|------|-----------|-----------------|-------|
| `postShorten_validUrl_returns201` | `POST /shorten { "url": "https://example.com" }` | 201 | Body has `code` (6 chars, alphanumeric) and `shortUrl` |
| `postShorten_sameUrlTwice_differentCodes` | Two identical POST requests | 201, 201 | Codes in responses differ |
| `postShorten_blankUrl_returns400` | `POST /shorten { "url": "" }` | 400 | Error message present |
| `postShorten_missingUrl_returns400` | `POST /shorten {}` | 400 | Error message present |
| `postShorten_ftpUrl_returns400` | `POST /shorten { "url": "ftp://bad.com" }` | 400 | Scheme validation |
| `postShorten_urlTooLong_returns400` | `POST /shorten { "url": "https://" + "a"*2100 }` | 400 | Length validation |
| `getCode_validCode_returns302` | `GET /<valid-code>` | 302 | `Location` header equals original URL |
| `getCode_unknownCode_returns404` | `GET /zzzzzz` | 404 | Error body present |
| `getCode_expiredCode_returns410` | Insert row with past `expires_at`; `GET /<code>` | 410 | Error body present |

### Edge Cases to Cover

1. **Code at expiry boundary:** `expiresAt` set to exactly `Instant.now()` — treated as expired (uses `isBefore`, which returns false for equal instants; use `!isAfter` or explicit `isBefore(now)` with note that boundary = expired).
2. **URL with special characters:** URL containing `%20`, Unicode characters, or query strings — stored and redirected verbatim.
3. **Collision retry exhaustion:** All 5 retries collide — service returns 500 (tested at unit level; not exercised in integration tests due to improbability with H2).
4. **URL length exactly 2048:** Should succeed (boundary test).
5. **URL length of 2049:** Should fail with 400.

---

## 8. Risk Assessment

### Risk 1 — Short Code Collision Under Concurrency
- **Likelihood:** Low (62^6 ≈ 56.8 billion combinations)
- **Impact:** Medium — a DB unique constraint violation would surface as an unhandled 500 without mitigation
- **Mitigation in plan:**
  - Application-level `existsByCode` check before saving (reduces probability further)
  - Up to 5 retries in `UrlShortenerService.shorten()` before throwing `IllegalStateException`
  - DB-level `UNIQUE` constraint as final safeguard
  - `GlobalExceptionHandler` can optionally catch `DataIntegrityViolationException` and retry or return 500 with a clear message
- **Test coverage:** `shorten_retriesOnCollisionAndSucceeds` and `shorten_throwsAfterMaxRetries` unit tests

### Risk 2 — Expired Records Accumulate Without Cleanup
- **Likelihood:** High over time
- **Impact:** Low initially; degrades query performance at scale
- **Mitigation in plan:**
  - `idx_short_urls_expires_at` index added in V1 migration to support future purge queries
  - Code comment in `UrlShortenerService` noting where a future `@Scheduled` purge job should be added
  - This is explicitly out of scope for v1 per the PRD

### Risk 3 — Malicious URLs in Redirect
- **Likelihood:** Medium
- **Impact:** Medium — service acts as a redirect relay for phishing/malware links
- **Mitigation in plan:**
  - `@Pattern(regexp = "https?://.+")` on `ShortenRequest.url` rejects non-http/https schemes
  - Logged at INFO level on every shorten and redirect (NFR-13)
  - Safe-browsing integration is a documented non-goal (PRD Section 3)

### Risk 4 — Database Unavailability
- **Likelihood:** Low in production, Medium in CI/local
- **Impact:** High — service is entirely DB-dependent
- **Mitigation in plan:**
  - `GlobalExceptionHandler` catches `DataAccessException` → HTTP 503 (NFR-08)
  - `application.properties` enables `/actuator/health` (NFR-09)
  - README documents Docker Compose `depends_on` pattern for local setup

### Risk 5 — Incorrect `shortUrl` Host Behind Proxy
- **Likelihood:** Medium in deployed environments behind nginx/ALB/Kubernetes ingress
- **Impact:** Medium — returned `shortUrl` uses internal hostname, not public domain
- **Mitigation in plan:**
  - `ServletUriComponentsBuilder.fromRequestUri(httpRequest).replacePath("")` derives host from incoming `Host` header
  - `server.forward-headers-strategy=native` in `application.properties` enables `X-Forwarded-Host` / `X-Forwarded-Proto` header processing
  - Integration test verifies `shortUrl` format is correct

### Rollback Plan
Since this is a **greenfield service** with no existing users or data:
1. Simply stop deploying the service.
2. If the database already has the migration applied, run `DROP TABLE short_urls CASCADE;` and drop the `flyway_schema_history` table.
3. No other services depend on this service in the initial deployment.

For future versions (post-v1), use Flyway's `V2__...` migration scripts; rollback scripts (`U2__...`) can be added if `flyway.undo-sql-migration-prefix` is configured.

---

## Appendix: File Summary

| File | Type | Task |
|------|------|------|
| `pom.xml` | Build | 1 |
| `src/main/resources/application.properties` | Config | 3 |
| `src/test/resources/application-test.properties` | Config | 3 |
| `src/main/resources/db/migration/V1__create_short_urls_table.sql` | Migration | 2 |
| `src/main/java/.../UrlShortenerApplication.java` | App | 4 |
| `src/main/java/.../entity/ShortUrl.java` | Entity | 5 |
| `src/main/java/.../repository/ShortUrlRepository.java` | Repository | 6 |
| `src/main/java/.../dto/ShortenRequest.java` | DTO | 7 |
| `src/main/java/.../dto/ShortenResponse.java` | DTO | 7 |
| `src/main/java/.../dto/ErrorResponse.java` | DTO | 7 |
| `src/main/java/.../exception/ShortUrlNotFoundException.java` | Exception | 8 |
| `src/main/java/.../exception/ShortUrlExpiredException.java` | Exception | 8 |
| `src/main/java/.../service/CodeGeneratorService.java` | Service | 9 |
| `src/main/java/.../service/UrlShortenerService.java` | Service | 10 |
| `src/main/java/.../exception/GlobalExceptionHandler.java` | Handler | 11 |
| `src/main/java/.../controller/UrlShortenerController.java` | Controller | 12 |
| `src/test/java/.../service/CodeGeneratorServiceTest.java` | Test | 13 |
| `src/test/java/.../service/UrlShortenerServiceTest.java` | Test | 14 |
| `src/test/java/.../controller/UrlShortenerControllerIntegrationTest.java` | Test | 15 |
| `README.md` | Docs | 16 |
