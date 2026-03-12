# Changes Summary: URL Shortener Service

## Files Created

### Build & Configuration
- `pom.xml` — Maven build file with Spring Boot 3.2.3, Java 17, JPA, PostgreSQL, Flyway, validation, test dependencies
- `src/main/resources/application.properties` — App config: datasource (env vars), Flyway, base-url property
- `src/main/resources/db/migration/V1__create_url_mappings.sql` — Flyway DDL: creates `url_mappings` table with unique index on `code`

### Main Source
- `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` — Spring Boot entry point
- `src/main/java/com/example/urlshortener/model/UrlMapping.java` — JPA entity for `url_mappings` table
- `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` — Request DTO with Bean Validation (`@NotBlank`, `@Pattern`)
- `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` — Response record with `code` and `shortUrl`
- `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` — Error record with `error` field
- `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java` — Spring Data JPA repository with `findByCode` and `existsByCode`
- `src/main/java/com/example/urlshortener/service/CodeGeneratorService.java` — Generates random 6-char alphanumeric codes using `SecureRandom`
- `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` — Core business logic: shorten (with retry), resolve, expiry check
- `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` — REST controller: `POST /shorten` (201) and `GET /{code}` (302)
- `src/main/java/com/example/urlshortener/exception/ShortCodeNotFoundException.java` — Custom exception → 404
- `src/main/java/com/example/urlshortener/exception/ShortCodeExpiredException.java` — Custom exception → 410
- `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping all exceptions to `ErrorResponse` JSON
- `src/main/java/com/example/urlshortener/config/AppConfig.java` — `@Bean Clock` (UTC) for injectable, testable time

### Test Source
- `src/test/java/com/example/urlshortener/service/CodeGeneratorServiceTest.java` — Unit tests: code length, charset, randomness
- `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java` — Unit tests: shorten/resolve flows, collision retry, expiry boundary conditions

## Files Modified

- `artifacts/patch.diff` — Generated unified diff of all changes (this run)
- `artifacts/changes.md` — This file

## Tests Written

### CodeGeneratorServiceTest (3 tests)
| Test | What it verifies |
|------|-----------------|
| `generate_returnsExactlySixCharacters` | Code is exactly 6 characters long |
| `generate_containsOnlyAlphanumericCharacters` | Code matches `[A-Za-z0-9]{6}` |
| `generate_producesRandomCodes` | 1000 calls produce ≥990 distinct values |

### UrlShortenerServiceTest (8 tests)
| Test | What it verifies |
|------|-----------------|
| `shorten_persistsMappingAndReturnsCode` | Response has correct code and shortUrl; `save` called once |
| `shorten_retriesOnCollision` | On two collisions, `generate()` is called 3 times; `save` called once |
| `shorten_throwsAfterMaxRetries` | `IllegalStateException` thrown after 5 consecutive collisions |
| `shorten_savesCorrectExpiryDate` | `expires_at = created_at + 30 days` exactly |
| `resolve_validCode_returnsOriginalUrl` | Valid, non-expired code returns the original URL |
| `resolve_expiredCode_throwsShortCodeExpiredException` | Code expired 1ms ago → `ShortCodeExpiredException` |
| `resolve_expiredCode_exactBoundary` | `expires_at == now` → `ShortCodeExpiredException` (expired at boundary) |
| `resolve_validCode_oneMillisecondBeforeExpiry` | `expires_at = now + 1ms` → returns URL (not expired) |
| `resolve_unknownCode_throwsShortCodeNotFoundException` | Unknown code → `ShortCodeNotFoundException` |

## How to Verify the Implementation

### Prerequisites
- Java 17+
- Maven 3.8+
- PostgreSQL 13+ running locally (or via Docker)

### Environment Variables
```bash
export DB_URL=jdbc:postgresql://localhost:5432/urlshortener
export DB_USERNAME=postgres
export DB_PASSWORD=secret
```

### Run Unit Tests (no database required)
```bash
./mvnw test
```

### Build the Application
```bash
./mvnw clean package -DskipTests
```

### Run the Application
```bash
./mvnw spring-boot:run
```

### Manual Smoke Tests
```bash
# Shorten a URL
curl -s -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.example.com/very/long/path?query=param"}' | jq .

# Follow redirect (use -L to follow, or inspect Location header)
curl -v http://localhost:8080/<code>

# Validation error — blank URL
curl -s -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":""}' | jq .

# Validation error — non-http URL
curl -s -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"ftp://example.com"}' | jq .

# Not found
curl -v http://localhost:8080/xxxxxx
```

### Acceptance Criteria Verification

| Criterion | How to verify |
|-----------|---------------|
| Short codes are unique and exactly 6 alphanumeric chars | `CodeGeneratorServiceTest` + DB `UNIQUE` constraint |
| Duplicate long URLs get a new code each time | Submit same URL twice; observe two different codes |
| Expired codes return 410 | `UrlShortenerServiceTest.resolve_expiredCode_*` tests |
| Unknown codes return 404 | `UrlShortenerServiceTest.resolve_unknownCode_throwsShortCodeNotFoundException` |
| Unit tests for shortening logic | `UrlShortenerServiceTest` |
| Unit tests for expiry check | `UrlShortenerServiceTest.resolve_expiredCode_*` |
