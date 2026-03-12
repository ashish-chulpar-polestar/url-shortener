# Changes Summary

## Files Created

### Build Configuration
- `pom.xml` — Spring Boot 3.2.3 Maven project with Java 17; dependencies: spring-boot-starter-web, data-jpa, validation, actuator, flyway-core, postgresql (runtime), spring-boot-starter-test, h2 (test)

### Application Configuration
- `src/main/resources/application.properties` — Datasource via env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), Flyway enabled, JPA ddl-auto=validate, forward-headers-strategy=native
- `src/test/resources/application-test.properties` — H2 in-memory datasource in PostgreSQL compatibility mode for tests

### Database Migration
- `src/main/resources/db/migration/V1__create_short_urls_table.sql` — Creates `short_urls` table with `id`, `code`, `original_url`, `created_at`, `expires_at` columns; unique constraint on `code`; indexes on `code` and `expires_at`

### Application Code
- `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` — Spring Boot entry point
- `src/main/java/com/example/urlshortener/entity/ShortUrl.java` — JPA entity mapping to `short_urls` table
- `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java` — Spring Data JPA repository with `findByCode` and `existsByCode`
- `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` — Request record with `@NotBlank`, `@Size(max=2048)`, `@Pattern(https?://.+)` validation
- `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` — Response record with `code` and `shortUrl`
- `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` — Uniform error response record
- `src/main/java/com/example/urlshortener/exception/ShortUrlNotFoundException.java` — RuntimeException for unknown codes (→ 404)
- `src/main/java/com/example/urlshortener/exception/ShortUrlExpiredException.java` — RuntimeException for expired codes (→ 410)
- `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping exceptions to 404/410/400/503 responses
- `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java` — SecureRandom-based 6-char alphanumeric code generator
- `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` — Business logic: shorten (with up to 5 collision retries) and resolve (with expiry check)
- `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` — REST controller: `POST /shorten` (201) and `GET /{code}` (302)

### Tests
- `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java` — Unit tests for CodeGeneratorService
- `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java` — Mockito-based unit tests for UrlShortenerService
- `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerIntegrationTest.java` — Spring Boot integration tests with H2

## Files Modified

- None (greenfield project)

## Tests Written

### CodeGeneratorServiceTest (unit)
- `generatedCode_hasLength6` — verifies output is exactly 6 characters
- `generatedCode_isAlphanumeric` — verifies output matches `[A-Za-z0-9]{6}`
- `generate_producesUniqueValues` — generates 10,000 codes, asserts no duplicates
- `generate_usesUpperAndLowerCase` — statistical check for uppercase and lowercase chars
- `generate_usesDigits` — statistical check for digit chars

### UrlShortenerServiceTest (unit, Mockito)
- `shorten_savesEntityWithExpiryIn30Days` — verifies expiry is set to now + 30 days (±2s)
- `shorten_returnsEntityWithGeneratedCode` — verifies entity uses code from generator
- `shorten_retriesOnCollisionAndSucceeds` — stubs first two codes as collisions, third succeeds
- `shorten_throwsAfterMaxRetries` — all 5 retries collide, expects IllegalStateException
- `resolve_throwsNotFoundException_whenCodeMissing` — empty Optional → ShortUrlNotFoundException
- `resolve_throwsExpiredException_whenExpired` — past expiresAt → ShortUrlExpiredException
- `resolve_returnsEntity_whenValid` — future expiresAt → entity returned
- `resolve_atExpiryBoundary_isExpired` — 1ms past expiry → ShortUrlExpiredException

### UrlShortenerControllerIntegrationTest (integration, H2)
- `postShorten_validUrl_returns201WithCodeAndShortUrl` — 201 with valid code and shortUrl
- `postShorten_sameUrlTwice_returnsDifferentCodes` — same URL produces distinct codes
- `postShorten_blankUrl_returns400` — blank url → 400
- `postShorten_missingUrl_returns400` — missing url field → 400
- `postShorten_ftpUrl_returns400` — ftp:// scheme → 400
- `postShorten_urlTooLong_returns400` — url > 2048 chars → 400
- `getCode_validCode_returns302WithLocation` — valid code → 302 with correct Location header
- `getCode_unknownCode_returns404` — unknown code → 404
- `getCode_expiredCode_returns410` — past expires_at → 410

## How to Verify the Implementation

### Prerequisites
- Java 17+
- Maven 3.x
- PostgreSQL running (or use Docker: `docker run -d -p 5432:5432 -e POSTGRES_DB=urlshortener -e POSTGRES_PASSWORD=postgres postgres:15`)

### Run Tests (uses H2 in-memory, no PostgreSQL needed)
```bash
mvn test
```

### Run the Application
```bash
export DB_URL=jdbc:postgresql://localhost:5432/urlshortener
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
mvn spring-boot:run
```

### Verify Endpoints
```bash
# Shorten a URL
curl -s -X POST http://localhost:8080/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://www.example.com/some/long/path"}' | jq .

# Redirect (follow redirect with -L, or inspect with -v)
curl -v http://localhost:8080/<code>

# Health check
curl http://localhost:8080/actuator/health
```

### Acceptance Criteria Verification
| Criteria | Test |
|----------|------|
| Short codes are exactly 6 alphanumeric characters | `CodeGeneratorServiceTest.generatedCode_hasLength6`, `generatedCode_isAlphanumeric` |
| Duplicate long URLs get a new code each time | `postShorten_sameUrlTwice_returnsDifferentCodes` |
| Expired codes return 410 | `getCode_expiredCode_returns410`, `resolve_throwsExpiredException_whenExpired` |
| Unknown codes return 404 | `getCode_unknownCode_returns404`, `resolve_throwsNotFoundException_whenCodeMissing` |
| Collision retry logic | `shorten_retriesOnCollisionAndSucceeds`, `shorten_throwsAfterMaxRetries` |
