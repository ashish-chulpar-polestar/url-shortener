The implementation plan has been saved to `artifacts/plan.md`. Here is a summary of what it covers:

---

## Plan Summary

### Architecture
A single-module Spring Boot 3.x REST API backed by PostgreSQL. The request flows through `UrlShortenerController` → `UrlShortenerService` → `ShortUrlRepository` → PostgreSQL. Flyway manages the schema; Spring Data JPA handles persistence.

### 14 Files to Create (greenfield project)
| Layer | File |
|-------|------|
| Build | `pom.xml` |
| Config | `src/main/resources/application.yml` |
| Migration | `db/migration/V1__create_short_urls.sql` |
| Entity | `entity/ShortUrl.java` |
| Repository | `repository/ShortUrlRepository.java` |
| DTOs (3) | `ShortenRequest`, `ShortenResponse`, `ErrorResponse` |
| Exceptions (2) | `CodeNotFoundException`, `CodeExpiredException` |
| Service | `UrlShortenerService.java` |
| Controller | `UrlShortenerController.java` |
| Error handler | `GlobalExceptionHandler.java` |
| Entry point | `UrlShortenerApplication.java` |
| Tests (2) | `UrlShortenerServiceTest`, `UrlShortenerControllerTest` |

### Key Design Decisions
- **No hardcoded credentials** — all secrets via env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BASE_URL`).
- **Expiry check in JVM** using `Instant.now()` for clock consistency within a request.
- **Collision retry loop** capped at 10 attempts; `DataIntegrityViolationException` from concurrent inserts is caught and retried.
- **`ddl-auto: validate`** — Hibernate validates schema against Flyway-managed tables, no auto-DDL.
- **9 service unit tests** + **6 controller slice tests** covering all acceptance criteria and edge cases.
