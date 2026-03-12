The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

### PRD Structure

**1. Executive Summary** — Concise one-paragraph description of the service, its core mechanism (short code generation + redirect), TTL model, and storage.

**2. Problem Statement** — Explains why long URLs are a problem in communication channels and why an owned, server-side shortener is preferable to third-party dependencies.

**3. Goals and Non-Goals** — 8 explicit goals drawn directly from the spec; 10 explicit non-goals (custom codes, analytics, auth, rate limiting, etc.) to set clear engineering scope.

**4. User Stories** — 7 stories covering the API consumer (shortening, response format, error states, deduplication behaviour) and the developer (unit test coverage).

**5. Functional Requirements** — 12 numbered, testable requirements split across three sub-sections:
- `POST /shorten` (FR-1 to FR-8)
- `GET /{code}` (FR-9 to FR-12)
- Data model table with column types and constraints
- Error response contract table

**6. Non-Functional Requirements** — 15 requirements across performance (p95 targets), security (parameterized queries, no credential hardcoding), reliability (bounded retry, 503 on DB loss), scalability (stateless, indexed `code` column), and observability.

**7. Technical Constraints** — Stack pinned to Spring Boot 3.x / Java 17+ / PostgreSQL / JUnit 5 / `SecureRandom`, with notes on `java.time` for expiry and Flyway/Liquibase recommendation.

**8. Success Metrics** — 7 measurable targets including code uniqueness, redirect success rate, expiry accuracy, unit test coverage (≥80%), and latency SLOs.

**9. Implementation Risks** — 7 risks with likelihood/impact ratings and concrete mitigations (collision retry, UTC timestamps, DB readiness probes, Flyway migrations, expiry boundary tests, open-redirect allowlist).

**Appendices** — Full HTTP request/response examples and an acceptance-criteria-to-requirement traceability matrix.
