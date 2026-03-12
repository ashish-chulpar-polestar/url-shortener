The implementation plan has been saved to `artifacts/plan.md`. Here's a summary of what was produced:

---

## Plan Summary

**Project type:** Greenfield — 0 existing source files, 21 new files to create.

### Architecture
A stateless Spring Boot 3.x microservice with three layers (Controller → Service → Repository/JPA) backed by PostgreSQL. A `Clock` bean is injected for deterministic testing; Flyway manages schema migrations; a `@ConfigurationProperties` bean validates `app.base-url` at startup and fails fast if unset.

### Key design decisions
| Decision | Rationale |
|---|---|
| `SecureRandom` for code generation | Non-predictable codes (FR-05) |
| Optimistic insert + catch `DataIntegrityViolationException` | DB UNIQUE constraint is the authoritative collision guard across concurrent instances (NFR-13) |
| `@Pattern` on `@PathVariable {code}` | Rejects malformed codes before any DB access (NFR-07) |
| `https?://.+` regex on request URL | Blocks `javascript:`, `data:`, `ftp:`, etc. (NFR-04) |
| No default for `app.base-url` | Fail-fast on misconfiguration (Risk 5) |
| `Clock` bean injection | Enables boundary condition tests for expiry (FR-22) |

### Implementation order (11 tasks)
1. Maven project skeleton + `pom.xml`
2. Flyway migration SQL
3. Configuration (`AppProperties`, `ClockConfig`, `application.properties`)
4. JPA entity + repository
5. DTOs and exception types
6. `CodeGeneratorService`
7. `UrlShortenerService` (core business logic)
8. Controller + `GlobalExceptionHandler`
9. Unit tests (25+ cases)
10. Integration tests (Testcontainers PostgreSQL)
11. GitHub Actions CI workflow
