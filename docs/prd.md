The PRD has been saved to `artifacts/prd.md`. Here's a summary of what was produced:

---

## PRD Summary — `GET /health` Endpoint

**Repository context:** The repo is greenfield (no source files yet), so the PRD was authored against standard Spring Boot 3.x / Java 17 conventions.

### Key decisions documented:

| Section | Highlights |
|---|---|
| **Problem Statement** | Explains the operational gap: no health endpoint means load balancers, Kubernetes probes, and CI/CD smoke tests cannot function safely |
| **Goals / Non-Goals** | Explicitly out-of-scope: Spring Actuator, DB connectivity checks, auth, rate limiting |
| **Functional Requirements** | 12 numbered, testable requirements covering HTTP contract, response schema, controller implementation, and timestamp format |
| **Non-Functional Requirements** | p99 < 10 ms, 1,000 RPS capacity, no sensitive info leakage, explicit Spring Security permit-list requirement |
| **Technical Constraints** | Java 17+ Records for the DTO, Jackson `write-dates-as-timestamps=false` for ISO 8601 serialization, `spring-boot-starter-web` dependency prerequisite |
| **Success Metrics** | 7 measurable criteria including 100% `200 OK` rate, latency target, and CI smoke test pass rate |
| **Implementation Risks** | 5 risks with mitigations — most critical: Spring Security blocking `/health` and Jackson serializing `Instant` as a number instead of a string |
| **Appendix A** | Non-normative reference implementation sketch (controller + test structure + expected HTTP interaction) |
