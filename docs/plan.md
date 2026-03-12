The implementation plan has been saved to `artifacts/plan.md`. Here's a summary of what was produced:

---

## What the plan covers

**Repository state:** Greenfield — only the PRD existed. No existing Java source, build config, or tests to modify.

### 1. Architecture Overview
A 3-layer Spring Boot MVC → Service → Repository design backed by PostgreSQL, with Flyway managing schema. Includes ASCII component diagram and both request flows (POST /shorten and GET /{code}).

### 2. Files to Create (24 total)
- `pom.xml` — Spring Boot 3.x + JPA + Flyway + Validation + JaCoCo
- `V1__create_url_mappings.sql` — Flyway migration with UNIQUE constraint + index on `code`
- `application.properties` — env-var-backed datasource config, `ddl-auto=validate`
- Entity, 3 DTOs (records), Repository, 2 Services, 3 Exceptions, Controller, GlobalExceptionHandler, main class
- `docker-compose.yml` with PostgreSQL health-check
- `.github/workflows/ci.yml` with Postgres service container

### 3. Files to Modify
None — greenfield project.

### 4. Database Schema
`url_mappings` table with `BIGSERIAL` PK, `VARCHAR(6) UNIQUE` code, `TEXT` original_url, `TIMESTAMPTZ` created_at/expires_at. Explicit index on `code`.

### 5–6. 18 Ordered Implementation Tasks
Each task specifies exact file(s), key code snippets, and what it depends on — ordered so the build compiles after each step.

### 7. Testing Strategy
23+ tests across 4 test classes covering: code generation properties, collision retry (success + exhaustion), expiry boundary (exactly at, 1s before, 1s after), HTTP layer (all status codes), and repository constraint enforcement. JaCoCo gate at 80% line coverage on service/controller packages.

### 8. Risk Assessment
7 risks with mitigations: collision retry (bounded at 5), clock skew (UTC throughout), DB unavailability (503 handler + health-check), open-redirect abuse (scheme allowlist), URL length (2048-char cap), H2/PostgreSQL dialect gap (Testcontainers), Flyway migration drift (never edit V1).
