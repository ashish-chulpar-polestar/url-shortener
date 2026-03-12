The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was generated:

---

**Repository findings:** The repo is a greenfield project — only an empty `README.md` exists. No existing code, framework configuration, or schema was present, so the PRD was authored entirely from the requirement specification.

---

**PRD structure at a glance:**

| Section | Key decisions documented |
|---------|--------------------------|
| **Executive Summary** | Lightweight, self-contained Spring Boot microservice; 2-endpoint API |
| **Problem Statement** | Eliminates external shortener dependency, enables expiration control, keeps links in-house |
| **Goals / Non-Goals** | Explicitly scopes out analytics, auth, rate limiting, custom codes, admin UI |
| **User Stories** | 7 stories covering API consumers, end users, and developers |
| **Functional Requirements** | 17 numbered, testable requirements across code generation, both endpoints, data model, and test coverage |
| **Non-Functional Requirements** | p95 latency targets (100 ms GET, 200 ms POST), SQL injection prevention, stateless design for horizontal scaling |
| **Technical Constraints** | Java 17 + Spring Boot + PostgreSQL 13+, full API contract with request/response examples, recommended project layout |
| **Success Metrics** | Quantified targets: 100% uniqueness, ≥90% service-layer test coverage, zero security findings |
| **Implementation Risks** | 7 risks with likelihood/impact ratings and concrete mitigations (clock abstraction for testing, Flyway for schema, `app.base-url` config property) |
