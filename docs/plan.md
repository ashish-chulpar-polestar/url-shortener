The implementation plan has been saved to `artifacts/plan.md`. Here's a summary of what was produced:

---

## Plan Summary

**Repository state:** Greenfield — no application code exists, only the PRD files and an empty README.

### Key architectural decisions

- **Package root:** `com.example.urlshortener` with controller / dto / entity / exception / repository / service sub-packages
- **Code generation:** `CodeGeneratorService` using `SecureRandom` over a 62-char alphabet, producing 6-char codes. Injected as a Spring `@Service` so it can be mocked in unit tests.
- **Collision handling:** Up to 5 retries with `existsByCode` check before save; DB `UNIQUE` constraint as final guard.
- **Expiry:** Evaluated server-side using `Instant.now()` (UTC) in `UrlShortenerService.resolve()`.
- **`shortUrl` construction:** `ServletUriComponentsBuilder` + `server.forward-headers-strategy=native` to correctly handle reverse proxies.
- **Error mapping:** `@RestControllerAdvice` handler covering 404, 410, 400 (validation), and 503 (DB failure).

### 20 files to create

| Layer | Files |
|-------|-------|
| Build/Config | `pom.xml`, `application.properties`, `application-test.properties` |
| Migration | `V1__create_short_urls_table.sql` |
| App code | Entry point, entity, repository, 3 DTOs, 2 exceptions, 2 services, exception handler, controller |
| Tests | `CodeGeneratorServiceTest`, `UrlShortenerServiceTest`, `UrlShortenerControllerIntegrationTest` |

### 16 ordered implementation tasks

Tasks go from scaffolding (pom.xml) → schema → config → entity → repository → DTOs → exceptions → services → controller → tests → README, with explicit dependency ordering.
