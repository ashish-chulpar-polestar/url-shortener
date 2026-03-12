The implementation plan has been saved to `artifacts/plan.md`. Here's a summary of what was produced:

---

## Plan Summary

**Repository state:** Greenfield — no source code exists yet. The entire project must be scaffolded from scratch.

### What the plan covers:

**1. Architecture Overview**
- ASCII component diagram showing the Controller → Service → Repository → PostgreSQL flow
- Both request flows (POST /shorten and GET /{code}) described step-by-step

**2. Files to Create (18 new files)**
- `pom.xml`, `application.properties`, Flyway migration SQL
- JPA entity (`UrlMapping`), repository (`UrlMappingRepository`)
- DTOs (`ShortenRequest`, `ShortenResponse`, `ErrorResponse`)
- Custom exceptions + `GlobalExceptionHandler` (uniform JSON error responses)
- `CodeGeneratorService` (6-char alphanumeric via `SecureRandom`)
- `UrlShortenerService` (shorten, resolve, collision retry, expiry check)
- `UrlShortenerController` (POST /shorten → 201, GET /{code} → 302/404/410)
- `AppConfig` (injectable `Clock` bean for testability)
- 2 unit test classes

**3. Database Schema**
- Full `V1__create_url_mappings.sql` DDL with `BIGSERIAL`, `TIMESTAMPTZ`, unique constraint + index on `code`

**4. API Contract**
- All status codes, request/response bodies fully specified

**5. 14 ordered implementation tasks** with file targets and dependency ordering

**6. Testing Strategy**
- 3 `CodeGeneratorServiceTest` cases (length, charset, randomness)
- 8 `UrlShortenerServiceTest` cases including all boundary conditions for expiry (exactly at `expires_at`, ±1ms)
- 8 edge cases (blank URL, ftp:// rejection, DB failure, duplicate URLs, etc.)

**7. Risk Assessment** with mitigations for all 7 PRD-identified risks plus a rollback plan
