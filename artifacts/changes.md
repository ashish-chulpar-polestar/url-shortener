# Changes Summary — URL Shortener Service

## Files Created

### Build
| File | Description |
|------|-------------|
| `pom.xml` | Maven build descriptor — Spring Boot 3.3.5, Java 21, all required dependencies |

### Application Entry Point
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/UrlShortenerApplication.java` | `@SpringBootApplication` main class with `@ConfigurationPropertiesScan` |

### Configuration
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/config/AppProperties.java` | Binds `app.base-url` property; validated with `@NotBlank` |
| `src/main/java/com/example/urlshortener/config/ClockConfig.java` | Provides `Clock.systemUTC()` bean for testable time injection |
| `src/main/resources/application.yml` | Main config: datasource, JPA (validate DDL), Flyway, Actuator, logging |
| `src/test/resources/application.yml` | Test config: H2 in-memory DB, `ddl-auto: create-drop`, Flyway disabled |

### Database Migration
| File | Description |
|------|-------------|
| `src/main/resources/db/migration/V1__create_short_urls.sql` | Creates `short_urls` table with `BIGSERIAL` PK, unique `code` constraint, and indexes |

### Entity & Repository
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/entity/ShortUrl.java` | JPA entity mapping to `short_urls` table using `OffsetDateTime` |
| `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java` | `JpaRepository` with `findByCode(String)` derived query |

### DTOs
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/dto/ShortenRequest.java` | `POST /shorten` request body; validates HTTP/HTTPS URL with `@NotBlank` + `@Pattern` |
| `src/main/java/com/example/urlshortener/dto/ShortenResponse.java` | Response with `code` and `shortUrl` fields |
| `src/main/java/com/example/urlshortener/dto/ErrorResponse.java` | Uniform error body with `error` string field |

### Exceptions
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/exception/CodeNotFoundException.java` | Thrown for unknown/invalid codes → HTTP 404 |
| `src/main/java/com/example/urlshortener/exception/CodeExpiredException.java` | Thrown for expired codes → HTTP 410 |
| `src/main/java/com/example/urlshortener/exception/CodeGenerationException.java` | Thrown when all 5 generation retries are exhausted → HTTP 500 |
| `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` mapping all exceptions to sanitized HTTP responses |

### Service & Controller
| File | Description |
|------|-------------|
| `src/main/java/com/example/urlshortener/service/UrlShortenerService.java` | Core logic: `generateCode()` (SecureRandom), `shorten()` (retry loop), `resolve()` (expiry check) |
| `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java` | `POST /shorten` → 200, `GET /{code}` → 302 |

## Files Modified

None — this is a greenfield project.

## Tests Written

### `UrlShortenerServiceTest` (13 tests)
| Test | Acceptance Criteria Covered |
|------|-----------------------------|
| `generateCode_returnsExactlySixChars` | Short codes are exactly 6 characters |
| `generateCode_returnsAlphanumericOnly` | Short codes are alphanumeric |
| `generateCode_producesDistinctValues` | Statistical uniqueness (1,000 samples, ≥ 990 distinct) |
| `shorten_persistsEntityWithCorrectFields` | Entity fields correctly populated |
| `shorten_returnsCorrectShortUrl` | `shortUrl` = `baseUrl` + `/` + `code` |
| `shorten_expiresAt30DaysFromCreation` | Each short URL expires after 30 days |
| `shorten_retriesOnCollision` | Retries on `DataIntegrityViolationException` |
| `shorten_throwsAfterFiveCollisions` | `CodeGenerationException` after 5 failed attempts |
| `resolve_returnsOriginalUrl_whenCodeValidAndNotExpired` | 302 redirect for valid, non-expired code |
| `resolve_throwsCodeNotFoundException_whenCodeNotInDB` | 404 for unknown code |
| `resolve_throwsCodeExpiredException_whenExpired` | 410 for expired code |
| `resolve_throwsCodeNotFoundException_forInvalidCodeFormat` | 404 for invalid code format (no DB call) |
| `resolve_throwsCodeNotFoundException_forShortCode` | 404 for too-short code (no DB call) |
| `resolve_notExpired_exactBoundary` | Boundary: `isBefore` is strict — exact `expiresAt` returns 410 |

### `UrlShortenerControllerTest` (9 tests)
| Test | Acceptance Criteria Covered |
|------|-----------------------------|
| `postShorten_returns200_withValidUrl` | POST valid URL returns 200 with code and shortUrl |
| `postShorten_returns400_whenUrlMissing` | Missing `url` field returns 400 |
| `postShorten_returns400_whenUrlBlank` | Blank `url` returns 400 |
| `postShorten_returns400_whenUrlNotHttpScheme` | FTP scheme returns 400 |
| `postShorten_returns400_whenUrlIsJavascriptScheme` | Javascript scheme returns 400 |
| `getCode_returns302_withLocationHeader` | Valid code returns 302 with `Location` header |
| `getCode_returns404_whenNotFound` | Unknown code returns 404 with error body |
| `getCode_returns410_whenExpired` | Expired code returns 410 with error body |
| `getCode_returns503_whenDbUnavailable` | DB failure returns 503 with error body |

## How to Verify the Implementation

### Prerequisites
- Java 21
- Maven 3.9+
- PostgreSQL 15+ (for running the app; not required for tests)

### Run Tests (H2 in-memory, no PostgreSQL needed)
```bash
mvn clean test
```

### Build Fat JAR
```bash
mvn clean package -DskipTests
```

### Run the Application
```bash
# Start PostgreSQL and set environment variables, then:
export DB_URL=jdbc:postgresql://localhost:5432/urlshortener
export DB_USER=postgres
export DB_PASSWORD=postgres
export APP_BASE_URL=http://localhost:8080

mvn spring-boot:run
```

### Verify Endpoints
```bash
# Shorten a URL
curl -s -X POST http://localhost:8080/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/very/long/path"}' | jq .

# Redirect (follow Location header)
curl -v http://localhost:8080/<code>

# Health check
curl http://localhost:8080/actuator/health
```

### Verify Acceptance Criteria
- Short codes are 6 alphanumeric characters: `generateCode_returnsExactlySixChars`, `generateCode_returnsAlphanumericOnly`
- Duplicate long URLs get new codes: service always calls `generateCode()` without deduplication
- Expired codes return 410: `getCode_returns410_whenExpired`, `resolve_throwsCodeExpiredException_whenExpired`
- Unknown codes return 404: `getCode_returns404_whenNotFound`, `resolve_throwsCodeNotFoundException_whenCodeNotInDB`
- Codes expire after 30 days: `shorten_expiresAt30DaysFromCreation`
