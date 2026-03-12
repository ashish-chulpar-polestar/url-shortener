The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

**PRD: URL Shortener Service** — 9 sections covering:

1. **Executive Summary** — Stateless Spring Boot REST API, 6-char alphanumeric codes, PostgreSQL-backed, 30-day expiry.

2. **Problem Statement** — Long URLs are unwieldy to share; no existing short-link infrastructure in the project.

3. **Goals / Non-Goals** — Clearly scopes in the two endpoints and expiry behavior; explicitly excludes analytics, auth, vanity URLs, rate limiting, and admin tooling.

4. **User Stories** — 6 stories covering: shortening, redirect, 404 handling, 410 handling, no-deduplication, and uniqueness guarantee.

5. **Functional Requirements** — 17 numbered, testable requirements across three areas: `POST /shorten`, `GET /{code}`, and code generation — including the full DDL schema and index definition.

6. **Non-Functional Requirements** — Performance targets (p95 < 300 ms / 100 ms), security rules (`SecureRandom`, parameterized queries, input length cap), reliability (graceful DB failure handling, Flyway migrations), scalability (stateless design), and observability (structured logs, Actuator health).

7. **Technical Constraints** — Spring Boot 3.x / Java 17, PostgreSQL + JPA, Flyway/Liquibase, JUnit 5 + Mockito, no hardcoded credentials.

8. **Success Metrics** — 7 measurable targets including 90%+ test coverage, zero critical security findings, and clean migration runs.

9. **Implementation Risks** — 5 identified risks (collision under concurrency, record accumulation, malicious URL redirect, DB startup dependency, proxied `shortUrl` host) each with concrete mitigations.

Two appendices provide the full API contract with request/response examples and a ready-to-use acceptance criteria checklist for engineering sign-off.
