The implementation plan has been saved to `artifacts/plan.md`. Here's a summary of what it covers:

---

### Key findings from repository exploration

The repository is **greenfield** — no Spring Boot project exists yet (only README and PRD files). Every file in the plan is a new creation.

---

### Plan highlights

**Architecture:** A classic 3-layer Spring Boot app — `Controller → Service → Repository` — with a `CodeGenerator` utility, domain exceptions, and a `GlobalExceptionHandler` that ensures all error responses are structured JSON.

**16 files to create**, organized into:
1. `pom.xml` + Maven Wrapper (Spring Boot 3.3.x, Java 17, Flyway, JPA, Validation, Actuator)
2. `UrlMapping` JPA entity with `@Column(unique = true)` on `code`
3. `UrlMappingRepository` with `findByCode(String code)`
4. 3 DTOs (`ShortenRequest`, `ShortenResponse`, `ErrorResponse`)
5. 3 domain exceptions + `GlobalExceptionHandler`
6. `CodeGenerator` using `SecureRandom` over a 62-char alphabet
7. `UrlShortenerService` with collision retry (up to 5 attempts) and UTC expiry check
8. `UrlShortenerController` — `POST /shorten` → 201, `GET /{code}` → 302/404/410
9. Flyway migration `V1__create_url_mappings.sql`
10. 3 unit test classes (fully mocked, no DB required) + GitHub Actions CI

**Notable design decisions:**
- `app.base-url` property makes the `shortUrl` host configurable (avoids proxy/container hostname issues)
- `DataIntegrityViolationException` caught on save → retry up to 5× (handles collision at DB level)
- All timestamps in UTC; `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` enforced
- `ddl-auto=validate` — Flyway owns schema, JPA only validates
