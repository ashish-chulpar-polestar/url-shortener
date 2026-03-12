# Implementation Plan — `GET /health` Endpoint

**Project:** URL Shortener (url-shortener)
**Feature:** Health Check Endpoint
**PRD Reference:** `artifacts/prd.md`
**Author:** Senior Software Architect
**Date:** 2026-03-12
**Branch:** `main`

---

## 1. Architecture Overview

### Repository State

The repository is **greenfield** — no source code, no build file, and no project structure exist yet. This implementation must bootstrap the entire Spring Boot project in addition to delivering the health check endpoint itself.

### How the Feature Fits

The health check endpoint is a **stateless, infrastructure-facing HTTP endpoint** that sits at the edge of the application. It has no dependencies on any service layer, repository, database, or external system. It is the simplest possible Spring MVC controller.

```
                    ┌─────────────────────────────────────┐
                    │         URL Shortener Service        │
                    │                                      │
  GET /health  ───► │  HealthController                    │
                    │    └── returns HealthResponse record  │
                    │         ├── status: "ok"             │
                    │         └── timestamp: Instant.now() │
                    │                                      │
                    │  [Future controllers go here]        │
                    └─────────────────────────────────────┘
```

### Component Roles

| Component | Role |
|---|---|
| `UrlShortenerApplication` | Spring Boot entry point (`@SpringBootApplication`), bootstraps embedded Tomcat |
| `HealthController` | `@RestController` handling `GET /health`; no service dependency |
| `HealthResponse` | Java 17 `record` DTO serialized to `{"status":"ok","timestamp":"..."}` by Jackson |
| `application.properties` | Configures `spring.jackson.serialization.write-dates-as-timestamps=false` to force ISO 8601 string output |
| `pom.xml` | Maven build descriptor; declares `spring-boot-starter-web`, Java 17, and Spring Boot 3.x parent |
| `HealthControllerTest` | JUnit 5 + MockMvc unit test asserting HTTP 200 and response body structure |

---

## 2. Files to Create

### 2.1 `pom.xml` (project root)

**Purpose:** Maven build descriptor. Bootstraps the project as a Spring Boot 3.x application targeting Java 17.

**Key contents:**
- `<parent>` set to `spring-boot-starter-parent:3.2.x` (or latest stable 3.x)
- `<groupId>`: `com.urlshortener`
- `<artifactId>`: `url-shortener`
- `<java.version>`: `17`
- Dependencies:
  - `spring-boot-starter-web` (scope: compile) — provides embedded Tomcat, Spring MVC, Jackson
  - `spring-boot-starter-test` (scope: test) — provides JUnit 5, MockMvc, AssertJ
- Plugin: `spring-boot-maven-plugin`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.3</version>
  </parent>

  <groupId>com.urlshortener</groupId>
  <artifactId>url-shortener</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

---

### 2.2 `src/main/java/com/urlshortener/UrlShortenerApplication.java`

**Purpose:** Spring Boot application entry point.

**Key contents:**
- Annotated with `@SpringBootApplication`
- Contains `public static void main(String[] args)` calling `SpringApplication.run(...)`

```java
package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

---

### 2.3 `src/main/java/com/urlshortener/controller/HealthController.java`

**Purpose:** `@RestController` that handles `GET /health`. Returns a `HealthResponse` record with `status` and `timestamp`.

**Key contents:**
- `@RestController` class annotation
- `@GetMapping("/health")` handler method `health()`
- Nested `HealthResponse` record (or separate file — see note below) with fields `String status` and `Instant timestamp`
- `Instant.now()` called inside the handler to generate a fresh timestamp per request

**Note on `HealthResponse` placement:** The record can live as either a nested record inside `HealthController` or a top-level class in the same package. A dedicated top-level class is preferred for navigability as the project grows.

```java
package com.urlshortener.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", Instant.now());
    }
}
```

---

### 2.4 `src/main/java/com/urlshortener/controller/HealthResponse.java`

**Purpose:** Response DTO. A Java 17 record that Jackson serializes to `{"status":"ok","timestamp":"..."}`.

**Key contents:**
- `public record HealthResponse(String status, Instant timestamp)`
- No additional annotations needed when `write-dates-as-timestamps=false` is configured (Jackson's `JavaTimeModule` is auto-configured by Spring Boot)

```java
package com.urlshortener.controller;

import java.time.Instant;

public record HealthResponse(String status, Instant timestamp) {}
```

---

### 2.5 `src/main/resources/application.properties`

**Purpose:** Application configuration. Ensures Jackson serializes `Instant` as an ISO 8601 string, not an epoch number.

**Key contents:**
```properties
spring.application.name=url-shortener
spring.jackson.serialization.write-dates-as-timestamps=false
```

---

### 2.6 `src/test/java/com/urlshortener/controller/HealthControllerTest.java`

**Purpose:** Unit test for `HealthController` using MockMvc (standalone setup — no Spring context required for this simple controller).

**Key contents:**
- `@ExtendWith(MockitoExtension.class)` or `@WebMvcTest(HealthController.class)` (see trade-off note)
- Standalone `MockMvc` setup for fast execution
- Test: `GET /health` returns HTTP 200
- Test: response body has `status` field equal to `"ok"`
- Test: response body has `timestamp` field present, non-null, and ISO 8601 format

```java
package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
            .standaloneSetup(new HealthController())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    @Test
    void health_returns200() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk());
    }

    @Test
    void health_returnsStatusOk() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void health_returnsTimestampAsIso8601String() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(jsonPath("$.timestamp").isString())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void health_responseHasExactlyTwoFields() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(jsonPath("$.length()").value(2));
    }
}
```

**Trade-off note:** Standalone MockMvc (shown above) is faster because it does not load the full Spring context. `@WebMvcTest(HealthController.class)` is an acceptable alternative if you prefer Spring's auto-configured `MockMvc` and plan to test security config later — it requires no `ObjectMapper` setup but is slower.

---

## 3. Files to Modify

This is a greenfield repository. There are **no existing source files to modify**. All listed files in Section 2 are net-new.

The only pre-existing files are:
- `README.md` — update after implementation to document the health endpoint (optional, out of scope for this plan)
- `artifacts/prd.md` — read-only reference; do not modify
- `docs/prd.md` — read-only reference; do not modify

---

## 4. Database Schema Changes

**None.** The health endpoint is fully stateless (FR-8). No database interaction is performed. No schema changes, migrations, or new tables are required.

---

## 5. API Changes

### New Endpoint

| Attribute | Value |
|---|---|
| HTTP Method | `GET` |
| Path | `/health` |
| Authentication | None (public) |
| Request Headers | None required |
| Request Body | None |
| Success Status | `200 OK` |
| Content-Type | `application/json` |

### Response Schema

```json
{
  "status": "ok",
  "timestamp": "2026-03-12T10:00:00.000000Z"
}
```

| Field | Type | Example | Notes |
|---|---|---|---|
| `status` | `string` | `"ok"` | Always `"ok"` while process is alive |
| `timestamp` | `string` | `"2026-03-12T10:00:00.000000Z"` | ISO 8601 UTC, dynamically generated per request via `Instant.now()` |

### No Modified Endpoints

No existing endpoints are modified (none exist yet).

---

## 6. Step-by-Step Implementation Tasks

### Task 1 — Bootstrap Maven project structure

**Description:** Create the Maven directory layout and `pom.xml`. This is the prerequisite for all subsequent tasks.

**Files affected:**
- `pom.xml` (create)
- `src/main/java/` (directory)
- `src/main/resources/` (directory)
- `src/test/java/` (directory)

**Dependencies:** None.

**Steps:**
1. Create `pom.xml` at the project root with `spring-boot-starter-parent:3.2.3`, `spring-boot-starter-web`, `spring-boot-starter-test`, and Java 17 source/target.
2. Create directory tree: `src/main/java/com/urlshortener/controller/`, `src/main/resources/`, `src/test/java/com/urlshortener/controller/`.
3. Verify `mvn validate` passes (no source files needed for validation).

---

### Task 2 — Create Spring Boot application entry point

**Description:** Add `UrlShortenerApplication.java` so the project can start.

**Files affected:**
- `src/main/java/com/urlshortener/UrlShortenerApplication.java` (create)

**Dependencies:** Task 1 (Maven structure must exist).

**Steps:**
1. Create `UrlShortenerApplication.java` with `@SpringBootApplication` and `main` method as shown in Section 2.2.
2. Verify `mvn compile` succeeds.

---

### Task 3 — Configure Jackson for ISO 8601 serialization

**Description:** Add `application.properties` with `write-dates-as-timestamps=false` to prevent Jackson from serializing `Instant` as an epoch number.

**Files affected:**
- `src/main/resources/application.properties` (create)

**Dependencies:** Task 1.

**Steps:**
1. Create `application.properties` with contents from Section 2.5.
2. This is a prerequisite for the timestamp field serializing correctly — must be done before writing tests.

---

### Task 4 — Create `HealthResponse` record

**Description:** Define the response DTO as a Java 17 record.

**Files affected:**
- `src/main/java/com/urlshortener/controller/HealthResponse.java` (create)

**Dependencies:** Task 2 (package must exist).

**Steps:**
1. Create `HealthResponse.java` as shown in Section 2.4.
2. Use `java.time.Instant` as the type for the `timestamp` field (not `String`) — let Jackson handle serialization.

---

### Task 5 — Create `HealthController`

**Description:** Implement the `@RestController` with the `GET /health` handler.

**Files affected:**
- `src/main/java/com/urlshortener/controller/HealthController.java` (create)

**Dependencies:** Task 4 (`HealthResponse` must exist).

**Steps:**
1. Create `HealthController.java` as shown in Section 2.3.
2. Annotate with `@RestController` (not `@Controller` — ensures `@ResponseBody` is applied automatically).
3. Map handler with `@GetMapping("/health")`.
4. Return `new HealthResponse("ok", Instant.now())`.
5. Run `mvn compile` to confirm no compilation errors.

---

### Task 6 — Write unit tests

**Description:** Create `HealthControllerTest` covering all test cases outlined in Section 7.

**Files affected:**
- `src/test/java/com/urlshortener/controller/HealthControllerTest.java` (create)

**Dependencies:** Task 5 (controller must be implemented).

**Steps:**
1. Create the test class with standalone MockMvc setup (ObjectMapper configured with `JavaTimeModule` and `WRITE_DATES_AS_TIMESTAMPS` disabled).
2. Implement the four test methods from Section 2.6.
3. Run `mvn test` — all tests must pass.

---

### Task 7 — Full build verification

**Description:** Run the complete Maven build to confirm compile, test, and package phases succeed.

**Files affected:** None (verification only).

**Dependencies:** Tasks 1–6.

**Steps:**
1. Run `mvn clean package`.
2. Confirm all 4 unit tests pass.
3. Optionally run `java -jar target/url-shortener-0.0.1-SNAPSHOT.jar` and `curl http://localhost:8080/health` to verify the live response.

---

## 7. Testing Strategy

### 7.1 Unit Tests

All tests reside in `HealthControllerTest` and use standalone MockMvc (no Spring context boot).

| Test Case | What It Verifies | FR/NFR Covered |
|---|---|---|
| `health_returns200` | HTTP response status is `200 OK` | FR-1 |
| `health_returnsStatusOk` | `Content-Type` is `application/json`; `$.status` equals `"ok"` | FR-2, FR-3, FR-4, FR-6, FR-9, FR-10 |
| `health_returnsTimestampAsIso8601String` | `$.timestamp` is present, is a string, is non-empty | FR-5, FR-12 |
| `health_responseHasExactlyTwoFields` | JSON object contains exactly 2 fields (no extras) | FR-3 |

### 7.2 Integration / Smoke Test (CI/CD)

After deployment, a smoke test script (shell or CI step) should:
```bash
RESPONSE=$(curl -sf http://<HOST>:8080/health)
STATUS=$(echo "$RESPONSE" | jq -r '.status')
[ "$STATUS" = "ok" ] || exit 1
```
This covers US-2 (Deployment Verification) and the "CI Smoke Test Pass Rate" success metric.

### 7.3 Edge Cases

| Scenario | Expected Behavior | How to Verify |
|---|---|---|
| Application starting slowly | Endpoint unavailable (connection refused) until fully started — correct behavior | Kubernetes `initialDelaySeconds` setting |
| Jackson not configured with `JavaTimeModule` | `timestamp` serializes as array `[seconds, nanos]` | Confirmed prevented by `application.properties` setting + standalone MockMvc test with explicit `ObjectMapper` |
| Query parameters passed (e.g., `GET /health?foo=bar`) | Still returns `200 OK` — extra params silently ignored (no `@RequestParam` binding) | Optional: add test `get("/health?foo=bar")` asserting `200` |
| `Content-Type: application/xml` request header (Accept header) | Returns `406 Not Acceptable` — acceptable because NG6 (XML not required) | No action needed |
| Spring Security added later | `/health` must be in permit-list or probes return `401` | See Risk 1 mitigation |

---

## 8. Risk Assessment

### Risk 1 — Spring Security Blocks the Endpoint (Medium/High)

**Scenario:** A future developer adds `spring-boot-starter-security` to `pom.xml`. By default, Spring Security blocks all endpoints with HTTP Basic auth, causing infrastructure probes to receive `401 Unauthorized`.

**Impact:** All Kubernetes liveness probes fail → pods restart in a loop → service outage.

**Mitigation:**
- Document in `README.md` that any Spring Security configuration MUST include:
  ```java
  http.authorizeHttpRequests(auth -> auth
      .requestMatchers("/health").permitAll()
      .anyRequest().authenticated()
  );
  ```
- When adding Spring Security in the future, add an integration test (`@SpringBootTest + TestRestTemplate`) that asserts `/health` returns `200` without credentials.

**Rollback:** Remove `spring-boot-starter-security` or add the permit-list configuration.

---

### Risk 2 — Timestamp Serializes as Epoch Number (Medium/Medium)

**Scenario:** `application.properties` is missing or `write-dates-as-timestamps` is not set to `false`. Jackson's default behavior serializes `java.time.Instant` as an array `[seconds, nanos]` or a decimal number.

**Impact:** Monitoring tools and contract tests expecting a string fail. The `health_returnsTimestampAsIso8601String` test catches this before it reaches production.

**Mitigation:** The `application.properties` setting (Task 3) and the test's explicit `ObjectMapper` configuration (Task 6) provide dual protection. The test will fail at build time if misconfigured.

**Rollback:** Correct `application.properties` and re-run `mvn test`.

---

### Risk 3 — Missing `spring-boot-starter-web` Dependency (Low/High)

**Scenario:** `pom.xml` is created without `spring-boot-starter-web`. The project compiles but does not start an embedded HTTP server; `@RestController` annotations have no effect.

**Impact:** The application starts but `/health` is unreachable.

**Mitigation:** `pom.xml` template in Task 1 explicitly includes `spring-boot-starter-web`. Verify with `mvn dependency:list | grep starter-web` after Task 1.

---

### Risk 4 — Wrong Base Package Breaks Component Scan (Low/Medium)

**Scenario:** The main application class (`UrlShortenerApplication`) is placed in a sub-package (e.g., `com.urlshortener.app`) that is not a parent of `com.urlshortener.controller`. Spring's component scan only covers sub-packages of the class annotated with `@SpringBootApplication`.

**Impact:** `HealthController` is not registered as a bean; `GET /health` returns `404`.

**Mitigation:** Place `UrlShortenerApplication` in the root package `com.urlshortener` (as specified in Task 2). All controller sub-packages will be scanned automatically.

---

### Risk 5 — Endpoint Removed or Path Changed Without Infrastructure Update (Low/High)

**Scenario:** A future refactor renames `/health` to `/api/health` or `/ping`.

**Impact:** All load balancer and Kubernetes probe configurations silently break.

**Mitigation:** Treat `/health` as a stable contract. Document the path in `README.md`. Any path change must be coordinated with DevOps to update probe configurations.

---

### Rollback Plan (General)

Because this is a greenfield project, the rollback for any failed implementation is to revert the relevant files via `git revert` or `git reset`. There is no existing production traffic or database to restore. The rollback plan is:

1. `git revert <commit>` or delete the feature branch.
2. Redeploy the previous (empty) state — infrastructure probes were not previously configured, so no probe failures occur.
3. Fix the implementation defect and re-deploy.

---

## Appendix — File Listing Summary

```
.
├── pom.xml                                                          [CREATE]
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/urlshortener/
│   │   │       ├── UrlShortenerApplication.java                     [CREATE]
│   │   │       └── controller/
│   │   │           ├── HealthController.java                        [CREATE]
│   │   │           └── HealthResponse.java                         [CREATE]
│   │   └── resources/
│   │       └── application.properties                              [CREATE]
│   └── test/
│       └── java/
│           └── com/urlshortener/
│               └── controller/
│                   └── HealthControllerTest.java                    [CREATE]
├── artifacts/
│   ├── prd.md                                                       [EXISTING]
│   └── plan.md                                                      [THIS FILE]
└── docs/
    └── prd.md                                                       [EXISTING]
```

**Total new files:** 6
**Modified files:** 0
**New dependencies:** `spring-boot-starter-web` (compile), `spring-boot-starter-test` (test scope)
**No database changes. No API modifications. No new external services.**
