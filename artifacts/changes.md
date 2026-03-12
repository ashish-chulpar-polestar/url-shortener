# Changes Summary — GET /health Endpoint

## Files Created

| File | Purpose |
|---|---|
| `pom.xml` | Maven build descriptor; Spring Boot 3.2.3 parent, Java 17, `spring-boot-starter-web` + `spring-boot-starter-test` |
| `src/main/java/com/urlshortener/UrlShortenerApplication.java` | `@SpringBootApplication` entry point; bootstraps embedded Tomcat |
| `src/main/java/com/urlshortener/controller/HealthController.java` | `@RestController` with `GET /health` handler returning `HealthResponse` |
| `src/main/java/com/urlshortener/controller/HealthResponse.java` | Java 17 `record` DTO with `String status` and `Instant timestamp` fields |
| `src/main/resources/application.properties` | Configures `spring.jackson.serialization.write-dates-as-timestamps=false` for ISO 8601 output |
| `src/test/java/com/urlshortener/controller/HealthControllerTest.java` | 4 standalone MockMvc unit tests |

## Files Modified

None. This is a greenfield project; all files are net-new.

## Tests Written

`HealthControllerTest` — 4 JUnit 5 tests using standalone MockMvc (no Spring context boot required):

| Test | What It Verifies |
|---|---|
| `health_returns200` | `GET /health` responds with HTTP 200 OK |
| `health_returnsStatusOk` | Response `Content-Type` is `application/json`; `$.status` equals `"ok"` |
| `health_returnsTimestampAsIso8601String` | `$.timestamp` is a non-empty string (ISO 8601, not an epoch number) |
| `health_responseHasExactlyTwoFields` | JSON object contains exactly 2 fields (`status` and `timestamp`) |

## How to Verify

### Build and test

```bash
mvn clean test
```

All 4 tests should pass.

### Full package build

```bash
mvn clean package
```

Produces `target/url-shortener-0.0.1-SNAPSHOT.jar`.

### Live smoke test

```bash
java -jar target/url-shortener-0.0.1-SNAPSHOT.jar &
curl -s http://localhost:8080/health | jq .
```

Expected response:

```json
{
  "status": "ok",
  "timestamp": "2026-03-12T10:00:00.123456Z"
}
```

### Acceptance criteria checklist

- [x] `GET /health` returns HTTP 200
- [x] Response body is `{"status":"ok","timestamp":"<ISO8601>"}`
- [x] `timestamp` is an ISO 8601 string (not an epoch number)
- [x] Response has exactly 2 fields
- [x] No external dependencies (stateless, no DB)
- [x] Unit tests cover all acceptance criteria
