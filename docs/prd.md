The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

## PRD Overview

### Structure
The document covers all 9 required sections plus 3 appendices (API contract, DB schema, configuration reference).

### Key decisions captured

**Functional Requirements (21 requirements across 4 areas)**
- `POST /shorten` — input validation (FR-01–03), code generation rules (FR-04–06), persistence (FR-07), response shape (FR-08–09)
- `GET /{code}` — lookup, 404 vs 410 distinction, 302 redirect with `Location` header (FR-10–14)
- Data model — exact PostgreSQL DDL with unique constraint and index (FR-15–18)
- Testing — unit test expectations for code generation and expiry logic, integration test scenarios (FR-19–21)

**Non-Functional Requirements**
- Performance targets: `GET /{code}` p99 < 100 ms, `POST /shorten` p99 < 300 ms
- Security: parameterized queries, `SecureRandom` for code generation, no stack traces in responses, HTTPS via reverse proxy
- Reliability: HikariCP connection pooling, health check via `/actuator/health`
- Scalability: stateless application tier for horizontal scaling

**9 Implementation Risks with mitigations**
Notable ones: open redirect/SSRF abuse, clock skew in expiry checks, unbounded table growth, concurrent collision handling

**8 Success Metrics** — all measurable, including collision rate < 0.01%, redirect accuracy 100%, >= 80% unit test coverage on service classes
