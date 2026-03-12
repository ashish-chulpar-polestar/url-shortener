Implementation plan saved to `artifacts/plan.md`. Here's a summary of what was produced:

---

## Plan Summary

**Repository state:** Greenfield — no source files exist. The implementation bootstraps the entire Spring Boot project.

### Files to Create (6 total)

| File | Purpose |
|---|---|
| `pom.xml` | Maven build descriptor; Spring Boot 3.2.x parent, Java 17, `spring-boot-starter-web` + `spring-boot-starter-test` |
| `UrlShortenerApplication.java` | `@SpringBootApplication` entry point in `com.urlshortener` |
| `HealthController.java` | `@RestController` with `@GetMapping("/health")` returning `HealthResponse("ok", Instant.now())` |
| `HealthResponse.java` | Java 17 `record` DTO with `String status` and `Instant timestamp` fields |
| `application.properties` | Sets `spring.jackson.serialization.write-dates-as-timestamps=false` to ensure ISO 8601 string output |
| `HealthControllerTest.java` | 4 standalone MockMvc unit tests covering HTTP 200, `status:"ok"`, timestamp as ISO 8601 string, and exactly 2 fields |

### Key Design Decisions

- **`HealthResponse` uses `Instant` (not `String`)** — Jackson serializes it, controlled by `application.properties`. This is cleaner than calling `.toString()` in the handler.
- **Standalone MockMvc** in tests — no Spring context boot required, so tests run fast. The `ObjectMapper` is explicitly configured with `JavaTimeModule` to mirror production behavior.
- **Root package placement** for `UrlShortenerApplication` — ensures `@SpringBootApplication`'s component scan covers `com.urlshortener.controller`.

### Top Risks

1. **Spring Security added later** blocks `/health` → must permit-list the path (mitigation code snippet included)
2. **Jackson serializes `Instant` as epoch** if `write-dates-as-timestamps=false` is omitted → caught by the unit test at build time
3. **Wrong base package** for the application class → `HealthController` not scanned → `404`
