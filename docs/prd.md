The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

**Repository state:** The repo is a clean greenfield project — only a blank `README.md` exists, so the full service must be built from scratch. This is reflected throughout the PRD.

**Document sections:**

| Section | Key content |
|---------|-------------|
| **Executive Summary** | Concise overview of the service and its greenfield status |
| **Problem Statement** | Why long URLs are a pain point; business and UX impact |
| **Goals / Non-Goals** | 9 explicit goals; 12 explicit non-goals (vanity URLs, analytics, auth, etc.) |
| **User Stories** | 6 user stories covering shortening, redirecting, 404/410 handling, duplicate submissions, and JSON response shape |
| **Functional Requirements** | 19 numbered, testable requirements across 4 categories: shortening, redirection, data model, and testing |
| **Non-Functional Requirements** | Performance targets (200ms p95 for GET, 500ms for POST), security (parameterized queries, no credential leakage), reliability, scalability, observability |
| **Technical Constraints** | Spring Boot 3.x, PostgreSQL, Java 17+, fixed 6-char code, 30-day expiry, no existing code |
| **Success Metrics** | 7 measurable criteria tied directly to acceptance criteria |
| **Implementation Risks** | 6 risks with likelihood/impact ratings and concrete mitigations (collision retry, clock skew, migration failures, open redirect, table growth, base URL misconfiguration) |
| **Appendices** | Full API contract with request/response examples and the exact PostgreSQL DDL |
