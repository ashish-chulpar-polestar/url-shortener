# Changes Summary

## Files Created

### Production Code

| File | Description |
|------|-------------|
| `pom.xml` | Maven build file with Spring Boot 3.2.3, Spring Data JPA, Spring Validation, Flyway, PostgreSQL driver, H2 (test scope) |
| `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` | Spring Boot entry point |
| `src/main/java/com/example/urlshortener/entity/UrlMapping.java` | JPA entity mapping to `url_mappings` table with `id`, `code`, `original_url`, `created_at`, `expires_at`; includes `isExpired()` helper |
| `src/main/java/com/example/urlshortener/repository/UrlMappingRepository.java` | Spring Data JPA repository with `findByCode` and `existsByCode` queries |
| `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` | Request DTO with Bean Validation (`@NotBlank`, `@Pattern` for URL format) |
| `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` | Response DTO containing `code` and `shortUrl` |
| `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` | Core business logic: 6-char alphanumeric code generation using `SecureRandom`, collision retry, 30-day expiry, lookup |
| `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` | REST controller: `POST /shorten` (201 Created) and `GET /{code}` (302 redirect / 404 / 410) |
| `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` for validation errors (400) and unexpected errors (500) |
| `src/main/resources/application.yml` | App config with DB credentials from env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), Flyway enabled |
| `src/main/resources/db/migration/V1__create_url_mappings.sql` | Flyway migration creating `url_mappings` table and index on `code` |

### Test Code

| File | Description |
|------|-------------|
| `src/test/resources/application.yml` | Test config using in-memory H2 database with Flyway disabled (DDL managed by Hibernate `create-drop`) |
| `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java` | Unit tests for service and entity logic (Mockito) |
| `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java` | `@WebMvcTest` slice tests for controller behaviour |

## Files Modified

None. This is a greenfield implementation on an empty repository.

## Tests Written

### `UrlShortenerServiceTest` (unit tests)
- `generateCode_returnsExactlySixAlphanumericCharacters` — verifies code length and character set
- `generateCode_producesVariousCodes` — verifies randomness (100 samples, distinct count > 1)
- `shorten_savesAndReturnsMappingWithCode` — verifies persistence and returned fields
- `shorten_setsExpiryThirtyDaysFromNow` — verifies 30-day expiry window
- `shorten_retriesWhenCodeAlreadyExists` — verifies collision-retry loop
- `findByCode_returnsEmptyWhenNotFound` — verifies Optional.empty() on miss
- `findByCode_returnsMappingWhenFound` — verifies returned mapping fields
- `isExpired_returnsFalseForFutureExpiry` — expiry check: not yet expired
- `isExpired_returnsTrueForPastExpiry` — expiry check: already expired
- `isExpired_returnsTrueWhenExpiryIsNow` — expiry check: boundary condition

### `UrlShortenerControllerTest` (WebMvcTest)
- `postShorten_returnsCreatedWithCodeAndShortUrl` — 201 + correct JSON body
- `postShorten_returnsBadRequestForBlankUrl` — 400 on empty string
- `postShorten_returnsBadRequestForInvalidUrl` — 400 on non-URL string
- `postShorten_returnsBadRequestForMissingBody` — 400 on missing `url` field
- `getCode_redirectsToOriginalUrl` — 302 with correct `Location` header
- `getCode_returns404ForUnknownCode` — 404 on unknown code
- `getCode_returns410ForExpiredCode` — 410 on expired mapping

## How to Verify

### Prerequisites
- Java 17+
- Maven 3.8+
- PostgreSQL running locally (or via Docker)

### Run Unit Tests (no DB required)
```bash
mvn test
```

### Run the Application
```bash
# Start PostgreSQL (example with Docker)
docker run -d --name pg -e POSTGRES_DB=urlshortener -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15

# Start the application
mvn spring-boot:run
```

### Manual Acceptance Tests
```bash
# Shorten a URL
curl -s -X POST http://localhost:8080/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.example.com/a/very/long/path"}' | jq .
# Expected: {"code":"<6chars>","shortUrl":"http://localhost:8080/<6chars>"}

# Follow the redirect
curl -v http://localhost:8080/<code>
# Expected: HTTP/1.1 302 with Location: https://www.example.com/a/very/long/path

# Unknown code
curl -v http://localhost:8080/xxxxxx
# Expected: HTTP/1.1 404

# Expired code (set expires_at in the past via SQL then test)
# UPDATE url_mappings SET expires_at = NOW() - INTERVAL '1 day' WHERE code = '<code>';
curl -v http://localhost:8080/<code>
# Expected: HTTP/1.1 410
```

### Acceptance Criteria Checklist
- [x] Short codes are unique and exactly 6 alphanumeric characters
- [x] Duplicate long URLs get a new code each time (no deduplication)
- [x] Expired codes return 410 Gone
- [x] Unknown codes return 404 Not Found
- [x] Unit tests for shortening logic (`UrlShortenerServiceTest`)
- [x] Unit tests for expiry check (`isExpired_*` tests)
