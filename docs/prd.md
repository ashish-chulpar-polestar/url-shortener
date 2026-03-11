The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

### PRD Overview — URL Shortener Service

**9 sections + 2 appendices:**

| Section | Key Content |
|---------|-------------|
| **Executive Summary** | Concise description of the service, its behavior, and HTTP semantics (302/404/410) |
| **Problem Statement** | Unwieldy URLs, lack of link lifecycle control, operational/security risks |
| **Goals & Non-Goals** | Goals map directly to the spec; non-goals explicitly rule out analytics, auth, custom codes, rate limiting, etc. |
| **User Stories** | 7 stories covering API consumers, end users, and developer needs |
| **Functional Requirements** | 23 numbered, testable requirements across code generation, both endpoints, persistence, and unit tests |
| **Non-Functional Requirements** | Latency targets (p99 <100ms GET, <200ms POST), security (no stack traces, random codes), reliability (retry on collision), scalability (DB-enforced uniqueness enables horizontal scaling) |
| **Technical Constraints** | Spring Boot 3.x, Java 17+, PostgreSQL 14+, Maven, JUnit 5 + Mockito |
| **Success Metrics** | 9 measurable criteria including 0 duplicate codes, 100% redirect accuracy, >=80% test coverage |
| **Implementation Risks** | 7 risks with likelihood/impact ratings and concrete mitigations (collision retry, URL validation, clock skew, Flyway, configurable base URL, expired-row cleanup, open redirect) |
| **Appendix A** | Full API contract with request/response examples for all outcomes |
| **Appendix B** | DDL for the `url_mappings` table with both recommended indexes |
