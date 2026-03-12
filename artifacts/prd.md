# Product Requirements Document
## Health Check Endpoint — `GET /health`

**Project:** URL Shortener (url-shortener)
**Repository:** https://github.com/ashish-chulpar-polestar/url-shortener
**Branch:** main
**Author:** Senior Product Manager
**Date:** 2026-03-12
**Version:** 1.0

---

## 1. Executive Summary

This document specifies requirements for adding a lightweight health check HTTP endpoint (`GET /health`) to the URL Shortener Spring Boot application. The endpoint returns a JSON payload containing a `status` field and an ISO 8601 `timestamp`, enabling automated infrastructure tooling (load balancers, container orchestrators, monitoring agents) to determine whether the application instance is alive and ready to serve traffic. This is a foundational operational capability that must exist before the service can be reliably deployed and managed in any environment beyond local development.

---

## 2. Problem Statement

### 2.1 Background

The URL Shortener service is a greenfield Spring Boot application. As it moves toward deployment in containerized or cloud-hosted environments, infrastructure components such as Kubernetes liveness/readiness probes, AWS Elastic Load Balancer health checks, and third-party uptime monitors require a stable, well-defined HTTP endpoint to poll for service health. Without such an endpoint:

- **Load balancers cannot determine instance availability.** Traffic may be routed to unhealthy instances, causing user-facing errors.
- **Container orchestrators cannot perform safe restarts.** Kubernetes cannot distinguish a crashed pod from a slow-starting one without a liveness probe target.
- **On-call engineers lack a single, canonical way to verify service responsiveness.** Ad-hoc workarounds (e.g., curling a functional endpoint) are fragile and couple health checking to business logic.
- **CI/CD pipelines cannot perform post-deployment smoke tests** without a dedicated, side-effect-free endpoint.

### 2.2 Impact

| Affected Party | Impact Without Health Check |
|---|---|
| Infrastructure / DevOps | Cannot configure LB or Kubernetes probes; deployments are risky |
| On-call / SRE | No fast triage path; must infer health from logs or metrics |
| CI/CD Pipeline | Cannot verify successful deployment with a smoke test |
| End Users | Potential traffic routing to unhealthy pods; degraded availability |

---

## 3. Goals and Non-Goals

### 3.1 Goals

- **G1:** Expose a `GET /health` HTTP endpoint that responds with HTTP `200 OK` when the application is running.
- **G2:** Return a JSON response body with exactly two fields: `status` (string, value `"ok"`) and `timestamp` (string, ISO 8601 UTC format, e.g. `"2026-03-12T10:00:00.000Z"`).
- **G3:** Implement the endpoint as a standard Spring Boot `@RestController` following the project's existing controller conventions.
- **G4:** Ensure the endpoint is unauthenticated and publicly accessible (no auth token required).
- **G5:** The endpoint must be stateless — it must not perform database queries, cache lookups, or any I/O beyond returning the timestamp.
- **G6:** Include a unit test that verifies the HTTP status code and response body structure.

### 3.2 Non-Goals

- **NG1:** Deep health checks (database connectivity, cache availability, downstream service checks). These belong in a separate `GET /health/details` or `/actuator/health` endpoint, if needed.
- **NG2:** Integration with Spring Boot Actuator. This PRD specifies a custom controller, not Actuator configuration.
- **NG3:** Authentication or authorization for the health endpoint.
- **NG4:** Rate limiting or throttling of the health endpoint.
- **NG5:** Metrics collection or tracing instrumentation for this specific endpoint.
- **NG6:** Support for content negotiation (XML, plain text). Only JSON is required.

---

## 4. User Stories

### 4.1 Primary Stories

**US-1 — Infrastructure Probe**
> As a **load balancer / Kubernetes liveness probe**, I want to call `GET /health` and receive a `200 OK` with `{"status":"ok","timestamp":"..."}` so that I can confirm the application instance is alive before routing traffic to it.

**US-2 — Deployment Verification**
> As a **CI/CD pipeline**, I want to call `GET /health` after a deployment completes so that I can confirm the new build is up and receiving requests before marking the deployment as successful.

**US-3 — On-Call Triage**
> As an **SRE / on-call engineer**, I want to curl `GET /health` from my terminal so that I can immediately confirm whether the service process is alive and responding to HTTP requests during an incident.

**US-4 — Monitoring Agent**
> As a **monitoring agent** (e.g., Datadog, PagerDuty synthetic), I want to poll `GET /health` on a configurable interval so that I can track uptime percentage and page on-call when the endpoint stops responding.

### 4.2 Developer Stories

**US-5 — Developer Smoke Test**
> As a **developer**, I want to call `GET /health` after starting the application locally so that I can confirm the service started successfully without having to trigger a business-logic flow.

**US-6 — Automated Test**
> As a **developer writing tests**, I want a unit test for the `HealthController` so that I can verify the response structure and status code are correct on every build.

---

## 5. Functional Requirements

### 5.1 Endpoint Contract

| Attribute | Value |
|---|---|
| HTTP Method | `GET` |
| Path | `/health` |
| Request Headers | None required |
| Request Body | None |
| Success HTTP Status | `200 OK` |
| Success Content-Type | `application/json` |
| Error Conditions | None expected (stateless; no I/O) |

### 5.2 Response Body Schema

```json
{
  "status": "ok",
  "timestamp": "2026-03-12T10:00:00.000Z"
}
```

| Field | Type | Description | Constraints |
|---|---|---|---|
| `status` | `string` | Static indicator of service health | Must always be the literal string `"ok"` when the application is running |
| `timestamp` | `string` | Current server time at the moment of the request | Must conform to ISO 8601 combined date-time format in UTC (e.g., `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`). Must be dynamically generated per request — not cached or static. |

**FR-1:** The endpoint MUST return HTTP status `200` for every request while the application is running.

**FR-2:** The response body MUST be valid JSON.

**FR-3:** The JSON object MUST contain exactly the keys `status` and `timestamp`. Additional fields must not be present unless explicitly added in a future revision of this PRD.

**FR-4:** The value of `status` MUST be the string `"ok"`.

**FR-5:** The value of `timestamp` MUST be a valid ISO 8601 date-time string in UTC, generated dynamically at request time using the server clock.

**FR-6:** The `Content-Type` response header MUST be `application/json`.

**FR-7:** The endpoint MUST NOT require any `Authorization` header or session cookie.

**FR-8:** The endpoint MUST NOT perform any database queries, file I/O, or network calls to other services.

### 5.3 Controller Implementation Requirements

**FR-9:** The controller MUST be annotated with `@RestController`.

**FR-10:** The handler method MUST be mapped with `@GetMapping("/health")`.

**FR-11:** The response object MUST be serializable to JSON by the project's configured `ObjectMapper` / Jackson library.

**FR-12:** The timestamp MUST use `java.time.Instant` (or `java.time.ZonedDateTime` normalized to UTC) and be serialized as an ISO 8601 string (not an epoch number).

---

## 6. Non-Functional Requirements

### 6.1 Performance

**NFR-1:** The endpoint MUST respond in under **10 ms** at the 99th percentile under normal operating conditions (no I/O means latency is bounded by JVM overhead alone).

**NFR-2:** The endpoint MUST be capable of handling at least **1,000 requests per second** without degradation, given that health probes from multiple infrastructure components may poll frequently and concurrently.

### 6.2 Security

**NFR-3:** The endpoint MUST NOT expose sensitive system information (JVM version, OS details, internal package names, stack traces) in its response body or headers.

**NFR-4:** If the application introduces authentication/authorization (Spring Security) in the future, the `/health` path MUST be explicitly permit-listed so that infrastructure probes continue to function without credentials.

**NFR-5:** The endpoint MUST NOT be susceptible to injection attacks. Because it accepts no input and performs no I/O, this requirement is satisfied by design — implementation must ensure no query parameter processing is added.

### 6.3 Reliability and Availability

**NFR-6:** The endpoint MUST be available for the entire lifecycle of the application process. It MUST NOT be gated behind feature flags or conditional startup beans.

**NFR-7:** A failure to generate the timestamp (e.g., system clock error) MUST result in an HTTP `500` response rather than returning a malformed or missing timestamp field, to prevent infrastructure tooling from misinterpreting bad data as a healthy response.

### 6.4 Observability

**NFR-8:** Access logs for `GET /health` SHOULD be filterable (e.g., via log pattern configuration) so that high-frequency probe traffic does not overwhelm application logs in production. This is a configuration concern, not a code concern.

### 6.5 Maintainability

**NFR-9:** The controller MUST reside in a dedicated class (`HealthController`) rather than being added as a method to an existing unrelated controller.

**NFR-10:** The response structure MUST be represented as a dedicated response record or class (`HealthResponse`) with named fields, not as a raw `Map<String, Object>`, to make the contract explicit and refactoring-safe.

---

## 7. Technical Constraints

### 7.1 Framework

- **Spring Boot** is the application framework. The controller must use Spring MVC conventions (`@RestController`, `@GetMapping`).
- **Jackson** (the default Spring Boot JSON serializer) will handle serialization. The `HealthResponse` class must be Jackson-compatible (public getters or use of `@JsonProperty`).

### 7.2 Java / Language Version

- The project is assumed to target **Java 17+** (LTS, standard for modern Spring Boot 3.x). Java Records may be used for the response DTO.
- If the project targets Java 11, Records are not available — a standard POJO with `@JsonProperty` annotations should be used instead.

### 7.3 Build System

- The project is assumed to use **Maven** (`pom.xml`) or **Gradle** (`build.gradle`). The implementation introduces no new dependencies — `spring-boot-starter-web` is the only dependency required and is expected to already be declared.

### 7.4 Package Structure

- The controller must follow the project's established package conventions. Recommended base package: `com.urlshortener` (or the project's root package). The controller class should reside in a sub-package such as `com.urlshortener.controller` or `com.urlshortener.web`.

### 7.5 Existing Spring Boot Actuator

- If `spring-boot-actuator` is added to the project in the future, the custom `/health` endpoint defined here MUST take precedence over Actuator's default `/actuator/health` path (they are on different paths and do not conflict). No special configuration is needed.

### 7.6 Greenfield Status

- The repository currently contains no source code. The health endpoint will be among the first implemented features. The implementation must also establish the project's initial Maven/Gradle and package structure, which is a prerequisite that may be tracked as a separate task.

---

## 8. Success Metrics

| Metric | Target | Measurement Method |
|---|---|---|
| **HTTP Status** | 100% of responses return `200 OK` | Automated test assertion; load balancer health check logs |
| **Response Schema Validity** | 100% of responses contain `status` and `timestamp` fields with correct types | Unit test; contract test |
| **Latency (p99)** | < 10 ms | APM tool (e.g., Datadog, Micrometer) or load test (e.g., k6, JMeter) |
| **Uptime Detection** | Load balancer / Kubernetes correctly marks instance healthy within 3 probe cycles | Infrastructure deployment log review |
| **CI Smoke Test Pass Rate** | 100% of post-deployment smoke tests pass after a healthy deployment | CI/CD pipeline run history |
| **Test Coverage** | `HealthController` has >= 1 unit test covering HTTP status and response body | Jacoco / build output |
| **Zero Auth Failures** | No `401` or `403` responses from infrastructure probes | Access log analysis |

---

## 9. Implementation Risks

### Risk 1 — Spring Security Blocks the Endpoint
**Likelihood:** Medium (if Spring Security is added to the project later)
**Impact:** High — all infrastructure probes start receiving `401 Unauthorized`, causing pod restarts and traffic disruption
**Mitigation:** When Spring Security is introduced, explicitly permit-list `/health` in the `SecurityFilterChain` configuration:
```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/health").permitAll()
    .anyRequest().authenticated()
);
```
This should be a standing checklist item for any Spring Security setup work.

---

### Risk 2 — Timestamp Serialized as Epoch Number Instead of ISO 8601 String
**Likelihood:** Medium — Jackson's default behavior for `java.time.Instant` without configuration is to serialize as an epoch second/nanosecond array or number.
**Impact:** Medium — monitoring tools or contract tests expecting a string will fail
**Mitigation:** Ensure `spring.jackson.serialization.write-dates-as-timestamps=false` is set in `application.properties`, or annotate the field with `@JsonSerialize(using = InstantSerializer.class)`, or register `JavaTimeModule` in the application's `ObjectMapper` bean.

---

### Risk 3 — Path Conflict with Spring Boot Actuator
**Likelihood:** Low — Actuator defaults to `/actuator/health`, not `/health`
**Impact:** Low — both endpoints would exist, potentially confusing consumers
**Mitigation:** If Actuator is added, document that `/health` is the canonical application health path and optionally disable Actuator's health endpoint via `management.endpoint.health.enabled=false` or change the Actuator base path.

---

### Risk 4 — Missing `spring-boot-starter-web` Dependency
**Likelihood:** Low-Medium — project is greenfield; no `pom.xml` exists yet
**Impact:** High — without the web starter, `@RestController` will not be available and the app will not start an embedded HTTP server
**Mitigation:** Confirm `spring-boot-starter-web` is declared in the build file as part of project bootstrapping. This is a prerequisite for any controller implementation.

---

### Risk 5 — Endpoint Removed or Moved Without Infrastructure Update
**Likelihood:** Low
**Impact:** High — all probes begin failing, triggering alerts or rolling restarts
**Mitigation:** Treat `/health` as a stable, versioned contract. Any change to the path or response schema requires a coordinated update to all infrastructure probe configurations. Document the endpoint path in the README and any deployment runbooks.

---

## Appendix A — Reference Implementation Sketch

The following is a non-normative reference to illustrate the expected implementation structure. It is not a substitute for engineering judgment.

```
src/
└── main/
    └── java/
        └── com/urlshortener/
            ├── UrlShortenerApplication.java
            └── controller/
                └── HealthController.java
src/
└── test/
    └── java/
        └── com/urlshortener/
            └── controller/
                └── HealthControllerTest.java
```

**HealthController.java (sketch)**
```java
@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", Instant.now().toString());
    }

    public record HealthResponse(String status, String timestamp) {}
}
```

**Expected HTTP interaction:**
```
GET /health HTTP/1.1
Host: localhost:8080

HTTP/1.1 200 OK
Content-Type: application/json

{"status":"ok","timestamp":"2026-03-12T10:00:00.000Z"}
```

---

## Appendix B — Out-of-Scope Future Enhancements

The following items were discussed and explicitly deferred:

| Item | Rationale for Deferral |
|---|---|
| `GET /health/details` with DB ping | Adds I/O risk and complexity; not needed for basic liveness checking |
| Spring Boot Actuator integration | Heavyweight dependency for what is a simple endpoint |
| Response field `version` (app version) | Useful for deployments, but increases coupling to build metadata; defer to a separate `/info` endpoint |
| Readiness vs. Liveness separation | Relevant for Kubernetes; implement when Kubernetes deployment manifests are being authored |
