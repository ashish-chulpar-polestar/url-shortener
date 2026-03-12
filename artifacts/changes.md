# Changes Summary — URL Shortener Service

## Files Created

### Build & Configuration
- `pom.xml` — Maven build descriptor with Spring Boot 3.2.3, Java 17, dependencies: web, data-jpa, actuator, validation, postgresql (runtime), flyway-core, spring-boot-starter-test
- `src/main/resources/application.yml` — Application configuration; credentials sourced from environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BASE_URL`)
- `src/main/resources/db/migration/V1__create_short_urls.sql` — Flyway migration creating the `short_urls` table and `idx_short_urls_code` index

### Application Entry Point
- `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` — `@SpringBootApplication` main class

### Domain Layer
- `src/main/java/com/example/urlshortener/entity/ShortUrl.java` — JPA entity mapping to `short_urls` table (`id`, `code`, `original_url`, `created_at`, `expires_at`)
- `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java` — Spring Data JPA repository with `findByCode` and `existsByCode`

### DTOs
- `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` — Request record with `@NotBlank` validation on `url`
- `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` — Response record with `code` and `shortUrl`
- `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` — Uniform error envelope record

### Exceptions
- `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java` — Signals unknown code (→ HTTP 404)
- `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java` — Signals expired code (→ HTTP 410)
- `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping all exceptions to structured `ErrorResponse` bodies

### Service & Controller
- `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` — Core business logic: `generateCode()`, `shorten(url)`, `resolve(code)` with collision-retry loop (up to `app.max-code-retries`)
- `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` — REST controller exposing `POST /shorten` and `GET /{code}`

## Files Modified

- None (greenfield project)

## Tests Written

### Unit Tests — `UrlShortenerServiceTest`
Location: `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

| Test | Description |
|------|-------------|
| `generateCode_matchesAlphanumericPattern` | 1000 codes all match `[A-Za-z0-9]{6}` |
| `generateCode_producesDistinctCodes` | 100 codes produce > 1 distinct value |
| `shorten_savesEntityAndReturnsResponse` | Happy path: entity saved, response has correct code and shortUrl |
| `shorten_retriesOnCollisionAndSucceeds` | First `existsByCode` → true, second → false; `save` called once |
| `shorten_throwsIllegalStateExceptionWhenRetriesExhausted` | All `existsByCode` → true; `IllegalStateException` thrown |
| `shorten_duplicateLongUrlsProduceDistinctCodes` | Two calls with same URL both succeed and save |
| `resolve_returnsShortUrlWhenValidAndNotExpired` | Entity with future `expiresAt` returned without exception |
| `resolve_throwsCodeExpiredExceptionWhenExpired` | Entity with past `expiresAt` throws `CodeExpiredException` |
| `resolve_throwsCodeNotFoundExceptionWhenAbsent` | `Optional.empty()` throws `CodeNotFoundException` |

### Controller Slice Tests — `UrlShortenerControllerTest`
Location: `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`

| Test | Description |
|------|-------------|
| `postShorten_returns200WithCodeAndShortUrl` | POST `/shorten` with valid URL → 200, `code` and `shortUrl` in body |
| `postShorten_returns400WhenUrlMissing` | POST `/shorten` with `{}` → 400, `error` field present |
| `postShorten_returns400WhenUrlEmpty` | POST `/shorten` with `{"url":""}` → 400 |
| `getCode_returns302WithLocationHeader` | GET `/{code}` with valid code → 302, `Location` header set |
| `getCode_returns404WhenCodeNotFound` | Service throws `CodeNotFoundException` → 404 |
| `getCode_returns410WhenCodeExpired` | Service throws `CodeExpiredException` → 410 |

## How to Verify the Implementation

### Prerequisites
- Java 17+
- Maven 3.8+ (or use `./mvnw`)
- PostgreSQL instance (or Docker)

### Environment Variables
```bash
export DB_URL=jdbc:postgresql://localhost:5432/urlshortener
export DB_USERNAME=appuser
export DB_PASSWORD=secret
export APP_BASE_URL=http://localhost:8080
```

### Run Unit Tests (no DB required)
```bash
mvn test
```

### Run Full Build
```bash
mvn clean verify
```

### Run the Application
```bash
mvn spring-boot:run
```

### Manual API Verification
```bash
# Shorten a URL
curl -X POST http://localhost:8080/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.example.com/some/long/path"}'
# → {"code":"aB3xYz","shortUrl":"http://localhost:8080/aB3xYz"}

# Follow a short code (use -L to follow redirect)
curl -L http://localhost:8080/aB3xYz

# Missing code → 404
curl -v http://localhost:8080/unknown
# → HTTP 404 {"error":"Short code not found: unknown"}
```

### Acceptance Criteria Checklist
- [x] Short codes are unique and exactly 6 alphanumeric characters (`generateCode_matchesAlphanumericPattern`)
- [x] Duplicate long URLs get a new code each time (`shorten_duplicateLongUrlsProduceDistinctCodes`)
- [x] Expired codes return 410 (`getCode_returns410WhenCodeExpired`, `resolve_throwsCodeExpiredExceptionWhenExpired`)
- [x] Unknown codes return 404 (`getCode_returns404WhenCodeNotFound`, `resolve_throwsCodeNotFoundExceptionWhenAbsent`)
- [x] Unit tests for shortening logic and expiry check (9 service tests, 6 controller tests)
