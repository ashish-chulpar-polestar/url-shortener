# AGILE IMPLEMENTATION PLAN
**Project:** URL Shortener Service
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

This project delivers a Spring Boot REST API that converts long URLs into 6-character alphanumeric short codes stored in a PostgreSQL database. Users POST a long URL to `/shorten` and receive a short code and full short URL; visiting `/{code}` issues an HTTP 302 redirect to the original URL. Short links expire after 30 days, returning HTTP 410 Gone; unknown codes return HTTP 404. The implementation uses Flyway for schema migrations, Spring Data JPA for data access, input validation via Jakarta Bean Validation, and Testcontainers for real-database integration testing.

---

## TECHNICAL ANALYSIS

### Recommended Stack (Greenfield)

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 21 | LTS release; required minimum for Spring Boot 3.2.x; virtual threads available |
| Build | Maven 3.9 / Spring Boot parent 3.2.0 | Standard enterprise build; Spring Boot BOM aligns all dependency versions |
| Framework | Spring Boot 3.2.0 (Web, Data JPA, Validation) | Rapid REST API development, auto-configuration, embedded Tomcat |
| Database | PostgreSQL 15 | Mandated by PRD; ACID compliance; TEXT column type for arbitrary-length URLs |
| Migrations | Flyway 10.6.0 (via spring-boot-starter) | Version-controlled schema evolution; runs automatically on startup |
| Testing | JUnit 5 + Mockito + Testcontainers 1.19.3 | Unit tests for logic isolation; integration tests against real PostgreSQL |
| Containerization | Docker + Docker Compose | Reproducible dev/CI environment; PostgreSQL sidecar |

### Project Structure

```
url-shortener/
├── src/
│   ├── main/
│   │   ├── java/com/example/urlshortener/
│   │   │   ├── UrlShortenerApplication.java
│   │   │   ├── config/
│   │   │   │   └── AppProperties.java
│   │   │   ├── controller/
│   │   │   │   └── UrlShortenerController.java
│   │   │   ├── service/
│   │   │   │   └── UrlShortenerService.java
│   │   │   ├── repository/
│   │   │   │   └── ShortUrlRepository.java
│   │   │   ├── entity/
│   │   │   │   └── ShortUrl.java
│   │   │   ├── dto/
│   │   │   │   ├── ShortenRequest.java
│   │   │   │   ├── ShortenResponse.java
│   │   │   │   └── ErrorResponse.java
│   │   │   └── exception/
│   │   │       ├── UrlNotFoundException.java
│   │   │       ├── UrlExpiredException.java
│   │   │       └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__create_short_urls_table.sql
│   └── test/
│       ├── java/com/example/urlshortener/
│       │   ├── service/
│       │   │   └── UrlShortenerServiceTest.java
│       │   └── controller/
│       │       └── UrlShortenerControllerIT.java
│       └── resources/
│           └── application-test.yml
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

### Integration Points

- PostgreSQL 15 via JDBC (Spring Data JPA / Hibernate ORM)
- Flyway schema migration on application startup
- No external API calls; all logic is self-contained within the single service

### Technical Constraints

- Short codes must be exactly 6 alphanumeric characters, unique, randomly generated using `SecureRandom`
- Duplicate long URLs receive new codes on every POST (no deduplication per PRD)
- Expired URLs must return HTTP 410 Gone; missing URLs HTTP 404
- Unit test coverage target: ≥80% for service layer
- All database interactions via Spring Data JPA derived queries (no string-concatenated SQL)

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.urlshortener` | **Group ID:** `com.example` | **Artifact ID:** `url-shortener`

### Overview

The backend is a single Spring Boot application with one controller, one service, one JPA entity, one repository, two custom exception classes, one global exception handler, and three DTOs. Flyway manages the PostgreSQL schema via a single V1 migration. Integration tests use Testcontainers' JDBC URL driver to spin up a real PostgreSQL 15 container automatically.

---

### Epic 1: Project Bootstrap

**Goal:** Create a buildable, runnable Spring Boot project skeleton with all required dependencies, configuration, and Docker infrastructure.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 1.1: Maven Project Setup

**As a** developer **I want** a correctly configured `pom.xml` **so that** all dependencies are resolvable and the project builds with `mvn package`.

**Background for implementer:** Spring Boot 3.2.x requires Java 17 minimum; Java 21 is set as the compile target for long-term support. The `spring-boot-maven-plugin` must be declared so that `mvn spring-boot:run` and the executable fat JAR work correctly. Testcontainers BOM is imported in `<dependencyManagement>` to align all `org.testcontainers:*` artifact versions without specifying each individually. The `flyway-database-postgresql` artifact (separate from `flyway-core` since Flyway 10) is required for PostgreSQL dialect support.

**Acceptance Criteria:**
- [ ] `pom.xml` at project root with parent `org.springframework.boot:spring-boot-starter-parent:3.2.0`
- [ ] All compile-scope dependencies present: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime)
- [ ] Testcontainers BOM `org.testcontainers:testcontainers-bom:1.19.3` imported; test-scoped `spring-boot-starter-test`, `testcontainers:postgresql`, `testcontainers:junit-jupiter` declared
- [ ] `mvn package -DskipTests` completes successfully

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `pom.xml`

Create the Maven project descriptor at the project root. Set `groupId` to `com.example`, `artifactId` to `url-shortener`, `version` to `0.0.1-SNAPSHOT`, and `packaging` to `jar`. Set the parent to `org.springframework.boot:spring-boot-starter-parent:3.2.0`. Add property `java.version` with value `21`. Add compile-scope dependencies: `org.springframework.boot:spring-boot-starter-web`, `org.springframework.boot:spring-boot-starter-data-jpa`, `org.springframework.boot:spring-boot-starter-validation`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (version `10.6.0`), `org.postgresql:postgresql` (scope `runtime`). In `<dependencyManagement><dependencies>`, import `org.testcontainers:testcontainers-bom:1.19.3` with `<type>pom</type><scope>import</scope>`. Add test-scoped dependencies: `org.springframework.boot:spring-boot-starter-test`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`. In `<build><plugins>`, declare `org.springframework.boot:spring-boot-maven-plugin`.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Application Entry Point and Configuration

**As a** developer **I want** a working `main` class, `application.yml`, and `AppProperties` **so that** the application starts and connects to PostgreSQL using environment-variable-driven configuration.

**Background for implementer:** All externalized configuration uses `${ENV_VAR:default}` syntax so the same artifact runs in Docker Compose (with real env vars) and locally (falling back to defaults). The `app.*` namespace is bound to `AppProperties` via `@ConfigurationProperties` to avoid scattering `@Value` annotations across multiple classes. Setting `spring.jpa.hibernate.ddl-auto=validate` ensures Hibernate validates the schema against the entity on startup but never auto-creates or alters tables — Flyway owns DDL.

**Acceptance Criteria:**
- [ ] `UrlShortenerApplication.java` with `@SpringBootApplication` and `public static void main` calling `SpringApplication.run`
- [ ] `application.yml` declares `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `spring.jpa.hibernate.ddl-auto: validate`, `spring.flyway.enabled: true`, `app.base-url`, `app.expiry-days: 30`
- [ ] `AppProperties.java` annotated `@ConfigurationProperties(prefix = "app")` and `@Component` with fields `baseUrl` and `expiryDays`

**Tasks:**

**Task 1.2.a — Create UrlShortenerApplication** — file: `src/main/java/com/example/urlshortener/UrlShortenerApplication.java`

Create class `UrlShortenerApplication` in package `com.example.urlshortener`. Annotate with `@SpringBootApplication`. Implement `public static void main(String[] args)` that calls `SpringApplication.run(UrlShortenerApplication.class, args)`.

**Task 1.2.b — Create application.yml** — file: `src/main/resources/application.yml`

Create the YAML configuration file with the following keys. Under `spring.datasource`: set `url` to `${DATABASE_URL:jdbc:postgresql://localhost:5432/urlshortener}`, `username` to `${DATABASE_USERNAME:postgres}`, `password` to `${DATABASE_PASSWORD:postgres}`. Under `spring.jpa.hibernate`: set `ddl-auto` to `validate`. Under `spring.jpa`: set `show-sql` to `false`. Under `spring.flyway`: set `enabled` to `true` and `locations` to `classpath:db/migration`. Under `app`: set `base-url` to `${APP_BASE_URL:http://localhost:8080}` and `expiry-days` to `30`.

**Task 1.2.c — Create AppProperties** — file: `src/main/java/com/example/urlshortener/config/AppProperties.java`

Create class `AppProperties` in package `com.example.urlshortener.config`. Annotate with `@Component` and `@ConfigurationProperties(prefix = "app")`. Declare private field `String baseUrl` (bound from config key `app.base-url` via Spring's relaxed binding of kebab-case to camelCase) and private field `int expiryDays` (bound from `app.expiry-days`). Generate standard getter `getBaseUrl()`, setter `setBaseUrl(String baseUrl)`, getter `getExpiryDays()`, and setter `setExpiryDays(int expiryDays)`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 1.3: Docker Infrastructure

**As a** developer **I want** a `Dockerfile` and `docker-compose.yml` **so that** the application and PostgreSQL can be started together with `docker-compose up --build`.

**Acceptance Criteria:**
- [ ] `Dockerfile` uses a two-stage build: JDK builder stage produces the fat JAR; JRE runtime stage runs it
- [ ] `docker-compose.yml` defines `db` (postgres:15-alpine) and `app` services with correct environment variable wiring
- [ ] `docker-compose up --build` starts both services and the app successfully connects to the DB

**Tasks:**

**Task 1.3.a — Create Dockerfile** — file: `Dockerfile`

Create a two-stage Dockerfile. Stage 1 (`builder`): base image `eclipse-temurin:21-jdk-alpine`, set `WORKDIR /build`, copy `pom.xml` and the full `src/` directory into `/build`, run `mvn package -DskipTests` (assumes Maven is available in the image or use `./mvnw` if a Maven wrapper is added). Stage 2 (runtime): base image `eclipse-temurin:21-jre-alpine`, set `WORKDIR /app`, copy `--from=builder /build/target/url-shortener-0.0.1-SNAPSHOT.jar app.jar`, expose port `8080`, set `ENTRYPOINT ["java", "-jar", "app.jar"]`.

**Task 1.3.b — Create docker-compose.yml** — file: `docker-compose.yml`

Create a Docker Compose file (version `"3.8"`). Define service `db`: image `postgres:15-alpine`, environment variables `POSTGRES_DB=urlshortener`, `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD=postgres`, ports `"5432:5432"`, volumes entry `postgres_data:/var/lib/postgresql/data`. Define service `app`: `build: .`, environment variables `DATABASE_URL=jdbc:postgresql://db:5432/urlshortener`, `DATABASE_USERNAME=postgres`, `DATABASE_PASSWORD=postgres`, `APP_BASE_URL=http://localhost:8080`, ports `"8080:8080"`, `depends_on: [db]`. Add top-level `volumes:` section declaring `postgres_data:`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Database and Persistence Layer

**Goal:** Establish the PostgreSQL schema via Flyway migration and create the JPA entity and repository required for URL mapping persistence.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Flyway Migration Script

**As a** developer **I want** the `short_urls` table created automatically on startup **so that** no manual DDL steps are required to set up a new environment.

**Background for implementer:** Flyway runs automatically when `spring.flyway.enabled=true` and the `flyway-core` / `flyway-database-postgresql` jars are on the classpath. Migration files must be placed at `src/main/resources/db/migration/` with the naming convention `V<version>__<description>.sql` (two underscores). The `expires_at` column is typed as `TIMESTAMP` (without time zone) because the service stores UTC `LocalDateTime` values — using `TIMESTAMPTZ` would require timezone conversion handling that is not needed here.

**Acceptance Criteria:**
- [ ] File `V1__create_short_urls_table.sql` present at `src/main/resources/db/migration/`
- [ ] Table `short_urls` created with columns `id`, `code`, `original_url`, `created_at`, `expires_at`
- [ ] `code` column has `UNIQUE` constraint named `uk_short_urls_code`
- [ ] Index `idx_short_urls_code` on column `code`

**Tasks:**

**Task 2.1.a — Create Flyway migration script** — file: `src/main/resources/db/migration/V1__create_short_urls_table.sql`

Create the SQL migration file. Write a single `CREATE TABLE short_urls` statement with the following column definitions: `id BIGSERIAL PRIMARY KEY`, `code VARCHAR(6) NOT NULL`, `original_url TEXT NOT NULL`, `created_at TIMESTAMP NOT NULL`, `expires_at TIMESTAMP NOT NULL`. Add a table-level constraint `CONSTRAINT uk_short_urls_code UNIQUE (code)` inside the `CREATE TABLE` statement. After the `CREATE TABLE` statement, add a separate statement: `CREATE INDEX idx_short_urls_code ON short_urls (code)`. This index accelerates the `SELECT ... WHERE code = ?` lookup executed on every redirect request.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 2.2: ShortUrl JPA Entity

**As a** developer **I want** a JPA entity class mapped to `short_urls` **so that** Hibernate can serialize and deserialize URL mapping rows without manual SQL.

**Background for implementer:** `@Column(name = "original_url")` is included explicitly because the Java field `originalUrl` does not exactly match the column name `original_url` when using Hibernate's default naming strategy (`PhysicalNamingStrategyStandardImpl`). Spring Boot's auto-configured `SpringPhysicalNamingStrategy` does handle camelCase-to-snake_case conversion, but explicit `@Column` annotations are preferred for clarity and to avoid surprises if the naming strategy is ever changed. `LocalDateTime` (not `ZonedDateTime`) is used to match the `TIMESTAMP WITHOUT TIME ZONE` column; the service layer is responsible for ensuring all values are in UTC.

**Acceptance Criteria:**
- [ ] Class `ShortUrl` in package `com.example.urlshortener.entity`, annotated `@Entity` and `@Table(name = "short_urls")`
- [ ] Field `id` is `Long`, annotated `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
- [ ] Fields `code` (String), `originalUrl` (String), `createdAt` (LocalDateTime), `expiresAt` (LocalDateTime) with explicit `@Column` annotations
- [ ] No-args constructor, all-args constructor, and getters/setters for all fields

**Tasks:**

**Task 2.2.a — Create ShortUrl entity** — file: `src/main/java/com/example/urlshortener/entity/ShortUrl.java`

Create class `ShortUrl` in package `com.example.urlshortener.entity`. Annotate the class with `@Entity` and `@Table(name = "short_urls")`. Declare the following private fields with annotations: `Long id` annotated `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)`; `String code` annotated `@Column(name = "code", nullable = false, length = 6)`; `String originalUrl` annotated `@Column(name = "original_url", nullable = false, columnDefinition = "TEXT")`; `LocalDateTime createdAt` annotated `@Column(name = "created_at", nullable = false)`; `LocalDateTime expiresAt` annotated `@Column(name = "expires_at", nullable = false)`. Generate a public no-args constructor, a public all-args constructor with parameters `(Long id, String code, String originalUrl, LocalDateTime createdAt, LocalDateTime expiresAt)`, and public getters and setters for every field.

**Complexity:** S | **Dependencies:** Story 2.1

---

#### Story 2.3: ShortUrlRepository

**As a** developer **I want** a Spring Data JPA repository **so that** the service can persist and look up `ShortUrl` records without writing boilerplate SQL.

**Acceptance Criteria:**
- [ ] Interface `ShortUrlRepository` in `com.example.urlshortener.repository`, annotated `@Repository`, extends `JpaRepository<ShortUrl, Long>`
- [ ] Method `Optional<ShortUrl> findByCode(String code)` declared (derived query)
- [ ] Method `boolean existsByCode(String code)` declared (used for collision detection in the service)

**Tasks:**

**Task 2.3.a — Create ShortUrlRepository** — file: `src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`

Create interface `ShortUrlRepository` in package `com.example.urlshortener.repository`. Annotate with `@Repository`. Extend `org.springframework.data.jpa.repository.JpaRepository<ShortUrl, Long>`. Declare method `Optional<ShortUrl> findByCode(String code)` — Spring Data automatically derives the query `SELECT s FROM ShortUrl s WHERE s.code = :code`. Declare method `boolean existsByCode(String code)` — Spring Data derives `SELECT COUNT(s) > 0 FROM ShortUrl s WHERE s.code = :code`; this is used by `UrlShortenerService.shorten` to detect a code collision before attempting to insert, avoiding a duplicate-key exception on the `uk_short_urls_code` unique constraint.

**Complexity:** S | **Dependencies:** Story 2.2

---

### Epic 3: Core Business Logic

**Goal:** Implement the URL shortening and resolution service that generates unique codes, persists URL mappings, and enforces expiry rules.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: UrlShortenerService

**As a** backend service **I want** a `UrlShortenerService` **so that** the controller can delegate all shortening and resolution logic to a single, testable component.

**Background for implementer:** Code generation uses `java.security.SecureRandom` (not `java.util.Random`) to ensure cryptographic unpredictability; URL shorteners are security-relevant because predictable codes enable enumeration attacks. The constant `ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"` has 62 characters, yielding 62^6 ≈ 56.8 billion possible codes — collision probability is negligible but handled by a retry loop of up to `MAX_CODE_GENERATION_ATTEMPTS = 10`. All timestamps are created as `LocalDateTime.now(ZoneOffset.UTC)` to ensure consistent UTC storage regardless of the JVM's default timezone.

**Acceptance Criteria:**
- [ ] Class `UrlShortenerService` in `com.example.urlshortener.service`, annotated `@Service`
- [ ] Method `public ShortUrl shorten(String originalUrl)` generates a unique 6-char code, sets `createdAt = now(UTC)` and `expiresAt = createdAt + 30 days`, saves and returns the entity
- [ ] Method `public String resolve(String code)` returns `originalUrl` for a valid non-expired code; throws `UrlNotFoundException` if not found; throws `UrlExpiredException` if expired
- [ ] Private method `generateCode()` uses `SecureRandom` and `ALPHANUMERIC_CHARS`
- [ ] SLF4J logger with INFO-level success logs and WARN-level logs for not-found and expired cases

**Tasks:**

**Task 3.1.a — Create UrlShortenerService** — file: `src/main/java/com/example/urlshortener/service/UrlShortenerService.java`

Create class `UrlShortenerService` in package `com.example.urlshortener.service`. Annotate with `@Service`. Add `private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class)` (import from `org.slf4j`). Declare private static final constants: `int CODE_LENGTH = 6`; `int MAX_CODE_GENERATION_ATTEMPTS = 10`; `String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"`. Declare instance fields `private final ShortUrlRepository shortUrlRepository` and `private final AppProperties appProperties`, injected via a `public` constructor annotated `@Autowired` (or relying on Spring's implicit single-constructor injection). Declare a private instance field `private final SecureRandom secureRandom = new SecureRandom()`. Implement private method `String generateCode()`: use a `StringBuilder` of capacity 6, loop `CODE_LENGTH` times calling `secureRandom.nextInt(ALPHANUMERIC_CHARS.length())` to pick a character index, append `ALPHANUMERIC_CHARS.charAt(index)`, and return `StringBuilder.toString()`. Implement `public ShortUrl shorten(String originalUrl)`: declare `String candidate = null`; loop `for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++)`: call `candidate = generateCode()`, if `!shortUrlRepository.existsByCode(candidate)` then `break`, else set `candidate = null` after the loop body; after the loop, if `candidate == null`, throw `new IllegalStateException("Unable to generate unique short code after " + MAX_CODE_GENERATION_ATTEMPTS + " attempts")`; create a `ShortUrl entity = new ShortUrl()`, call `entity.setCode(candidate)`, `entity.setOriginalUrl(originalUrl)`, `entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC))`, `entity.setExpiresAt(entity.getCreatedAt().plusDays(appProperties.getExpiryDays()))`; call `ShortUrl saved = shortUrlRepository.save(entity)`; log at INFO: `log.info("Shortened url code={} expiresAt={}", saved.getCode(), saved.getExpiresAt())`; return `saved`. Implement `public String resolve(String code)`: call `Optional<ShortUrl> opt = shortUrlRepository.findByCode(code)`; if `opt.isEmpty()`, log at WARN `log.warn("Short code not found code={}", code)` and throw `new UrlNotFoundException(code)`; declare `ShortUrl shortUrl = opt.get()`; if `shortUrl.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))`, log at WARN `log.warn("Short code expired code={} expiresAt={}", code, shortUrl.getExpiresAt())` and throw `new UrlExpiredException(code)`; log at INFO `log.info("Resolved code={} to url={}", code, shortUrl.getOriginalUrl())`; return `shortUrl.getOriginalUrl()`.

**Complexity:** M | **Dependencies:** Story 2.3, Story 1.2, Story 5.1

---

### Epic 4: REST API Layer

**Goal:** Expose the shortening and redirection endpoints via a Spring MVC controller backed by validated DTOs.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 4.1: Request and Response DTOs

**As a** developer **I want** well-typed DTO classes **so that** controller input is validated and JSON output is structured and predictable.

**Acceptance Criteria:**
- [ ] `ShortenRequest` in `com.example.urlshortener.dto` with field `String url` annotated `@NotBlank` and `@URL`
- [ ] `ShortenResponse` in `com.example.urlshortener.dto` with fields `String code` and `String shortUrl`
- [ ] `ErrorResponse` in `com.example.urlshortener.dto` with fields `String error` and `String message`

**Tasks:**

**Task 4.1.a — Create ShortenRequest DTO** — file: `src/main/java/com/example/urlshortener/dto/ShortenRequest.java`

Create class `ShortenRequest` in package `com.example.urlshortener.dto`. Declare private field `String url`. Annotate `url` with `@NotBlank(message = "url must not be blank")` (from `jakarta.validation.constraints.NotBlank`) and `@org.hibernate.validator.constraints.URL(message = "url must be a valid URL")`. Generate a public no-args constructor, a getter `getUrl()`, and a setter `setUrl(String url)`. The `@URL` annotation rejects strings that are not well-formed HTTP/HTTPS URLs; it originates from `hibernate-validator` which is included transitively via `spring-boot-starter-validation`.

**Task 4.1.b — Create ShortenResponse DTO** — file: `src/main/java/com/example/urlshortener/dto/ShortenResponse.java`

Create class `ShortenResponse` in package `com.example.urlshortener.dto`. Declare private fields `String code` and `String shortUrl`. Generate a public no-args constructor, a public all-args constructor `public ShortenResponse(String code, String shortUrl)` that sets both fields, getters `getCode()` and `getShortUrl()`. Jackson serializes this to `{ "code": "abc123", "shortUrl": "http://host/abc123" }` using field names directly (camelCase is preserved as-is).

**Task 4.1.c — Create ErrorResponse DTO** — file: `src/main/java/com/example/urlshortener/dto/ErrorResponse.java`

Create class `ErrorResponse` in package `com.example.urlshortener.dto`. Declare private fields `String error` and `String message`. Generate a public no-args constructor, a public all-args constructor `public ErrorResponse(String error, String message)`, and getters `getError()` and `getMessage()`. This DTO is returned by `GlobalExceptionHandler` for all error responses, serialized as `{ "error": "NOT_FOUND", "message": "..." }`.

**Complexity:** S | **Dependencies:** None

---

#### Story 4.2: UrlShortenerController

**As an** API consumer **I want** `POST /shorten` and `GET /{code}` endpoints **so that** I can create short URLs and follow them via HTTP redirect.

**Background for implementer:** The redirect endpoint returns `ResponseEntity<Void>` with `HttpStatus.FOUND` (302) and a `Location` header pointing to the original URL. Spring's `HttpHeaders.setLocation(URI)` requires a `URI` object, so the original URL string must be wrapped with `URI.create(originalUrl)`. Exception handling (404, 410) is entirely delegated to `GlobalExceptionHandler`; the controller method must not contain any try/catch blocks — this keeps the controller thin and makes error behavior consistent and centrally testable.

**Acceptance Criteria:**
- [ ] Class `UrlShortenerController` in `com.example.urlshortener.controller`, annotated `@RestController`
- [ ] `POST /shorten` mapped method accepts `@Valid @RequestBody ShortenRequest`, returns `ResponseEntity<ShortenResponse>` with HTTP 201 Created
- [ ] `GET /{code}` mapped method returns `ResponseEntity<Void>` with HTTP 302 and `Location` header set to the original URL
- [ ] SLF4J logger with INFO-level log per endpoint invocation

**Tasks:**

**Task 4.2.a — Create UrlShortenerController** — file: `src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`

Create class `UrlShortenerController` in package `com.example.urlshortener.controller`. Annotate with `@RestController`. Add `private static final Logger log = LoggerFactory.getLogger(UrlShortenerController.class)`. Declare constructor-injected fields `private final UrlShortenerService urlShortenerService` and `private final AppProperties appProperties`. Implement method `public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request)` annotated with `@PostMapping("/shorten")`: call `ShortUrl shortUrl = urlShortenerService.shorten(request.getUrl())`; log at INFO `log.info("Shorten request url={} code={}", request.getUrl(), shortUrl.getCode())`; construct `ShortenResponse response = new ShortenResponse(shortUrl.getCode(), appProperties.getBaseUrl() + "/" + shortUrl.getCode())`; return `ResponseEntity.status(HttpStatus.CREATED).body(response)`. Implement method `public ResponseEntity<Void> redirect(@PathVariable String code)` annotated with `@GetMapping("/{code}")`: call `String originalUrl = urlShortenerService.resolve(code)`; log at INFO `log.info("Redirect request code={}", code)`; construct `HttpHeaders headers = new HttpHeaders()`; call `headers.setLocation(URI.create(originalUrl))`; return `ResponseEntity.status(HttpStatus.FOUND).headers(headers).build()`.

**Complexity:** M | **Dependencies:** Story 3.1, Story 4.1

---

### Epic 5: Exception Handling

**Goal:** Implement custom exception classes and a global handler that maps them to correct HTTP status codes with a consistent JSON error body.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 5.1: Custom Exception Classes

**As a** developer **I want** typed exceptions for not-found and expired URL conditions **so that** the service layer communicates failure outcomes without coupling to HTTP semantics.

**Acceptance Criteria:**
- [ ] `UrlNotFoundException` in `com.example.urlshortener.exception`, extends `RuntimeException`, constructor `(String code)`, message `"Short code not found: " + code`
- [ ] `UrlExpiredException` in `com.example.urlshortener.exception`, extends `RuntimeException`, constructor `(String code)`, message `"Short code has expired: " + code`

**Tasks:**

**Task 5.1.a — Create UrlNotFoundException** — file: `src/main/java/com/example/urlshortener/exception/UrlNotFoundException.java`

Create class `UrlNotFoundException` in package `com.example.urlshortener.exception` that extends `RuntimeException`. Implement a single public constructor `public UrlNotFoundException(String code)` that calls `super("Short code not found: " + code)`. This exception is thrown by `UrlShortenerService.resolve` when `shortUrlRepository.findByCode(code)` returns `Optional.empty()`. It is mapped to HTTP 404 Not Found by `GlobalExceptionHandler`.

**Task 5.1.b — Create UrlExpiredException** — file: `src/main/java/com/example/urlshortener/exception/UrlExpiredException.java`

Create class `UrlExpiredException` in package `com.example.urlshortener.exception` that extends `RuntimeException`. Implement a single public constructor `public UrlExpiredException(String code)` that calls `super("Short code has expired: " + code)`. This exception is thrown by `UrlShortenerService.resolve` when `shortUrl.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))` is true. It is mapped to HTTP 410 Gone by `GlobalExceptionHandler`.

**Complexity:** S | **Dependencies:** None

---

#### Story 5.2: GlobalExceptionHandler

**As an** API consumer **I want** structured JSON error responses **so that** I receive meaningful HTTP status codes and error messages for all failure scenarios.

**Background for implementer:** `@RestControllerAdvice` is used (not `@ControllerAdvice`) so that `@ExceptionHandler` return values are automatically serialized as JSON without requiring `@ResponseBody` on each handler method. Validation failures from `@Valid` throw `MethodArgumentNotValidException`; its field errors are collapsed into a single comma-separated message string for simplicity. Spring Boot's default error handling (`BasicErrorController`) is bypassed for these exception types since `@RestControllerAdvice` takes precedence.

**Acceptance Criteria:**
- [ ] Class `GlobalExceptionHandler` in `com.example.urlshortener.exception`, annotated `@RestControllerAdvice`
- [ ] Handler for `UrlNotFoundException` returns `ErrorResponse` with HTTP 404, `error = "NOT_FOUND"`
- [ ] Handler for `UrlExpiredException` returns `ErrorResponse` with HTTP 410, `error = "GONE"`
- [ ] Handler for `MethodArgumentNotValidException` returns `ErrorResponse` with HTTP 400, `error = "VALIDATION_ERROR"`
- [ ] SLF4J logger with WARN-level log for each handler

**Tasks:**

**Task 5.2.a — Create GlobalExceptionHandler** — file: `src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in package `com.example.urlshortener.exception`. Annotate with `@RestControllerAdvice`. Add `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`. Implement method `public ResponseEntity<ErrorResponse> handleNotFound(UrlNotFoundException ex)` annotated with `@ExceptionHandler(UrlNotFoundException.class)`: log at WARN `log.warn("URL not found: {}", ex.getMessage())`; return `ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", ex.getMessage()))`. Implement method `public ResponseEntity<ErrorResponse> handleExpired(UrlExpiredException ex)` annotated with `@ExceptionHandler(UrlExpiredException.class)`: log at WARN `log.warn("URL expired: {}", ex.getMessage())`; return `ResponseEntity.status(HttpStatus.GONE).body(new ErrorResponse("GONE", ex.getMessage()))`. Implement method `public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex)` annotated with `@ExceptionHandler(MethodArgumentNotValidException.class)`: collect all field error default messages from `ex.getBindingResult().getFieldErrors()` by streaming and joining with `", "` into `String message`; log at WARN `log.warn("Validation error: {}", message)`; return `ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR", message))`.

**Complexity:** S | **Dependencies:** Story 5.1, Story 4.1

---

### Epic 6: Testing

**Goal:** Achieve ≥80% unit test coverage on the service layer and validate the full HTTP request/response cycle with integration tests against a real PostgreSQL instance via Testcontainers.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 6.1: Unit Tests for UrlShortenerService

**As a** developer **I want** unit tests for `UrlShortenerService` **so that** shortening logic and expiry enforcement are validated in isolation without a database.

**Background for implementer:** `ShortUrlRepository` and `AppProperties` are Mockito mocks. The `shorten` method calls `LocalDateTime.now(ZoneOffset.UTC)` internally, so tests for expiry windows use a tolerance of ±1 day around the expected 30-day mark. The `save` mock is stubbed with `thenAnswer(inv -> inv.getArgument(0))` so it returns the same `ShortUrl` instance passed to it, allowing the returned entity's fields to be inspected. The `existsByCode` stub returns `false` by default so code generation succeeds on the first attempt.

**Acceptance Criteria:**
- [ ] `UrlShortenerServiceTest` in `src/test/java/com/example/urlshortener/service/`, annotated `@ExtendWith(MockitoExtension.class)`
- [ ] Test `shorten_shouldReturnShortUrlWithSixCharCode`: `result.getCode()` matches regex `[A-Za-z0-9]{6}`
- [ ] Test `shorten_shouldSetExpiryToThirtyDaysFromNow`: `result.getExpiresAt()` is after `now + 29 days` and before `now + 31 days`
- [ ] Test `resolve_shouldThrowUrlNotFoundExceptionWhenCodeMissing`: asserts `UrlNotFoundException` with message `"Short code not found: xxxxxx"`
- [ ] Test `resolve_shouldThrowUrlExpiredExceptionWhenCodeExpired`: entity with `expiresAt = now - 1 day` causes `UrlExpiredException` with message `"Short code has expired: testco"`
- [ ] Test `resolve_shouldReturnOriginalUrlForValidCode`: entity with `expiresAt = now + 10 days` returns `"https://example.com"`

**Tasks:**

**Task 6.1.a — Create UrlShortenerServiceTest** — file: `src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

Create class `UrlShortenerServiceTest` in package `com.example.urlshortener.service`. Annotate with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock ShortUrlRepository shortUrlRepository` and `@Mock AppProperties appProperties`. Declare `@InjectMocks UrlShortenerService urlShortenerService`. Annotate a `@BeforeEach` setup method that calls: `when(appProperties.getExpiryDays()).thenReturn(30)`; `when(shortUrlRepository.existsByCode(anyString())).thenReturn(false)`; `when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0))`. Write test method `shorten_shouldReturnShortUrlWithSixCharCode` annotated `@Test`: call `ShortUrl result = urlShortenerService.shorten("https://example.com")`; assert `result.getCode() != null`; assert `assertTrue(result.getCode().matches("[A-Za-z0-9]{6}"))`. Write test `shorten_shouldSetExpiryToThirtyDaysFromNow` annotated `@Test`: call `ShortUrl result = urlShortenerService.shorten("https://example.com")`; assert `assertTrue(result.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(29)))`; assert `assertTrue(result.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusDays(31)))`. Write test `resolve_shouldThrowUrlNotFoundExceptionWhenCodeMissing` annotated `@Test`: call `when(shortUrlRepository.findByCode("xxxxxx")).thenReturn(Optional.empty())`; call `UrlNotFoundException ex = assertThrows(UrlNotFoundException.class, () -> urlShortenerService.resolve("xxxxxx"))`; assert `assertEquals("Short code not found: xxxxxx", ex.getMessage())`. Write test `resolve_shouldThrowUrlExpiredExceptionWhenCodeExpired` annotated `@Test`: create `ShortUrl expired = new ShortUrl()`; call `expired.setCode("testco")`, `expired.setOriginalUrl("https://example.com")`, `expired.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(31))`, `expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))`; call `when(shortUrlRepository.findByCode("testco")).thenReturn(Optional.of(expired))`; call `UrlExpiredException ex = assertThrows(UrlExpiredException.class, () -> urlShortenerService.resolve("testco"))`; assert `assertEquals("Short code has expired: testco", ex.getMessage())`. Write test `resolve_shouldReturnOriginalUrlForValidCode` annotated `@Test`: create `ShortUrl valid = new ShortUrl()`; call `valid.setCode("abc123")`, `valid.setOriginalUrl("https://example.com")`, `valid.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))`, `valid.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(10))`; call `when(shortUrlRepository.findByCode("abc123")).thenReturn(Optional.of(valid))`; call `String url = urlShortenerService.resolve("abc123")`; assert `assertEquals("https://example.com", url)`.

**Complexity:** M | **Dependencies:** Story 3.1, Story 5.1

---

#### Story 6.2: Integration Tests for UrlShortenerController

**As a** developer **I want** integration tests that exercise the full HTTP stack against a real PostgreSQL database **so that** I can verify end-to-end routing, persistence, and error responses.

**Background for implementer:** Testcontainers' JDBC URL approach (`jdbc:tc:postgresql:15:///testdb`) automatically starts a Docker container when Spring resolves the datasource — no explicit `@Container` annotation is needed. `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)` is required to prevent Spring Boot Test from replacing the configured datasource with an in-memory H2 database. `@ActiveProfiles("test")` activates `application-test.yml` which overrides the datasource URL. The redirect test requires a raw `RestTemplate` with `SimpleClientHttpRequestFactory` configured with `setFollowRedirects(false)` because `TestRestTemplate` by default follows redirects, which would mask the 302 status.

**Acceptance Criteria:**
- [ ] `UrlShortenerControllerIT` in `src/test/java/com/example/urlshortener/controller/`, annotated `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@ActiveProfiles("test")`
- [ ] Test `postShorten_shouldReturn201WithCodeAndShortUrl`: status 201, `body.code` matches `[A-Za-z0-9]{6}`, `body.shortUrl` ends with `body.code`
- [ ] Test `getRedirect_shouldReturn302WithLocationHeader`: status 302, `Location` header equals `"https://example.com"`
- [ ] Test `getRedirect_shouldReturn404ForUnknownCode`: status 404, `body.error` equals `"NOT_FOUND"`
- [ ] Test `postShorten_shouldReturn400ForInvalidUrl`: status 400, `body.error` equals `"VALIDATION_ERROR"`

**Tasks:**

**Task 6.2.a — Create application-test.yml** — file: `src/test/resources/application-test.yml`

Create the test profile configuration file. Under `spring.datasource`: set `url` to `jdbc:tc:postgresql:15:///testdb`, `driver-class-name` to `org.testcontainers.jdbc.ContainerDatabaseDriver`, `username` to `test`, `password` to `test`. Under `spring.jpa.hibernate`: set `ddl-auto` to `none` (Flyway manages the schema exclusively). Under `spring.flyway`: set `enabled` to `true` and `locations` to `classpath:db/migration`. Under `app`: set `base-url` to `http://localhost` and `expiry-days` to `30`.

**Task 6.2.b — Create UrlShortenerControllerIT** — file: `src/test/java/com/example/urlshortener/controller/UrlShortenerControllerIT.java`

Create class `UrlShortenerControllerIT` in package `com.example.urlshortener.controller`. Annotate with `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, `@ActiveProfiles("test")`, and `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)`. Inject `@Autowired TestRestTemplate restTemplate`. Write test `postShorten_shouldReturn201WithCodeAndShortUrl` annotated `@Test`: create `ShortenRequest req = new ShortenRequest(); req.setUrl("https://example.com")`; call `ResponseEntity<ShortenResponse> response = restTemplate.postForEntity("/shorten", req, ShortenResponse.class)`; assert `assertEquals(HttpStatus.CREATED, response.getStatusCode())`; assert `assertTrue(response.getBody().getCode().matches("[A-Za-z0-9]{6}"))`; assert `assertTrue(response.getBody().getShortUrl().endsWith(response.getBody().getCode()))`. Write test `getRedirect_shouldReturn302WithLocationHeader` annotated `@Test`: POST to `/shorten` with `url = "https://example.com"` to obtain a `String code`; create `SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); factory.setFollowRedirects(false)`; create `RestTemplate noRedirectTemplate = new RestTemplate(factory)`; inject the port with `@LocalServerPort int port`; call `ResponseEntity<Void> resp = noRedirectTemplate.getForEntity("http://localhost:" + port + "/" + code, Void.class)`; assert `assertEquals(HttpStatus.FOUND, resp.getStatusCode())`; assert `assertEquals("https://example.com", resp.getHeaders().getLocation().toString())`. Write test `getRedirect_shouldReturn404ForUnknownCode` annotated `@Test`: call `ResponseEntity<ErrorResponse> resp = restTemplate.getForEntity("/zzz999", ErrorResponse.class)`; assert `assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode())`; assert `assertEquals("NOT_FOUND", resp.getBody().getError())`. Write test `postShorten_shouldReturn400ForInvalidUrl` annotated `@Test`: create `ShortenRequest req = new ShortenRequest(); req.setUrl("not-a-url")`; call `ResponseEntity<ErrorResponse> resp = restTemplate.postForEntity("/shorten", req, ErrorResponse.class)`; assert `assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode())`; assert `assertEquals("VALIDATION_ERROR", resp.getBody().getError())`.

**Complexity:** M | **Dependencies:** Story 4.2, Story 5.2, Story 6.2.a

---

### Backend API Contracts

```
POST /shorten

Request Headers:
  Content-Type: application/json

Request Body:
  url   String   Required   Must be a valid HTTP/HTTPS URL (non-blank); validated by @NotBlank + @URL

Success Response — 201 Created:
  code       String   6-character alphanumeric short code (e.g. "abc123")
  shortUrl   String   Full short URL (e.g. "http://localhost:8080/abc123")

Error Response — 400 Bad Request:
  error     String   "VALIDATION_ERROR"
  message   String   Comma-separated field validation error messages

Error Code Reference:
  VALIDATION_ERROR   400   url field is blank or not a valid URL format

---

GET /{code}

Path Parameters:
  code   String   Required   6-character alphanumeric short code

Success Response — 302 Found:
  Location header set to the original long URL

Error Response — 404 Not Found:
  error     String   "NOT_FOUND"
  message   String   "Short code not found: {code}"

Error Response — 410 Gone:
  error     String   "GONE"
  message   String   "Short code has expired: {code}"

Error Code Reference:
  NOT_FOUND   404   Short code does not exist in the database
  GONE        410   Short code exists but expiresAt is before current UTC time
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 100ms for GET /{code}; p95 < 200ms for POST /shorten; single-instance target |
| Logging | SLF4J + Logback (Spring Boot default); INFO for successful operations; WARN for not-found/expired/validation; ERROR for unexpected exceptions; parameterized message templates only (no string concatenation) |
| Metrics | Spring Boot Actuator (optional); no explicit metric requirement in PRD |
| Security | No SQL string concatenation; all queries via Spring Data JPA derived methods; `@Valid` + `@URL` on POST body; `SecureRandom` for code generation |
| Rate Limiting | Not required per PRD |
| Testing | ≥80% line coverage on service layer; unit tests for all service methods; integration tests for all four controller scenarios |
| Health / Docs | Spring Boot Actuator `/actuator/health` available by default; no Swagger/OpenAPI required per PRD |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `UrlShortenerController` | `UrlShortenerService` | Delegates all shortening and resolution logic |
| `UrlShortenerController` | `AppProperties` | Reads `app.base-url` to construct `shortUrl` in 201 response body |
| `UrlShortenerService` | `ShortUrlRepository` | Persists new `ShortUrl` entities and looks up by code |
| `UrlShortenerService` | `AppProperties` | Reads `app.expiry-days` to compute `expiresAt = createdAt + N days` |
| `UrlShortenerService` | `UrlNotFoundException` | Thrown when `findByCode` returns empty |
| `UrlShortenerService` | `UrlExpiredException` | Thrown when `expiresAt` is before current UTC time |
| `ShortUrlRepository` | `ShortUrl` | JPA repository typed to the `ShortUrl` entity class |
| `GlobalExceptionHandler` | `UrlNotFoundException` | Maps to HTTP 404 with `error = "NOT_FOUND"` |
| `GlobalExceptionHandler` | `UrlExpiredException` | Maps to HTTP 410 with `error = "GONE"` |
| `GlobalExceptionHandler` | `ErrorResponse` | Constructs consistent JSON error body for all error responses |
| `UrlShortenerController` | `ShortenRequest`, `ShortenResponse` | Input deserialization and output serialization |
| `UrlShortenerServiceTest` | `ShortUrlRepository` (mock) | Mocked to isolate service from database in unit tests |
| `UrlShortenerServiceTest` | `AppProperties` (mock) | Mocked to control `expiryDays` value in tests |
| `UrlShortenerControllerIT` | `application-test.yml` | Activates Testcontainers JDBC URL for real PostgreSQL |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — `pom.xml` is the foundation; no code compiles without it
2. **Story 1.2** — Application entry point and `application.yml`; Spring context cannot start without these
3. **Story 5.1** — Exception classes have no dependencies; define early so `UrlShortenerService` can reference them
4. **Story 4.1** — DTO classes have no dependencies; define early so controller and exception handler can reference them
5. **Story 2.1** — Flyway migration script; Flyway validates schema on boot so the table must be defined before the entity
6. **Story 2.2** — `ShortUrl` entity; depends on migration having established the table schema
7. **Story 2.3** — `ShortUrlRepository`; depends on the `ShortUrl` entity
8. **Story 3.1** — `UrlShortenerService`; depends on repository, `AppProperties`, and exception classes
9. **Story 5.2** — `GlobalExceptionHandler`; depends on exception classes and `ErrorResponse` DTO
10. **Story 4.2** — `UrlShortenerController`; depends on service, DTOs, and `AppProperties`
11. **Story 1.3** — Docker files; can be added once the application builds and runs
12. **Story 6.1** — Unit tests; depend on service and exception classes (can start after Story 3.1)
13. **Story 6.2** — Integration tests; require the full application stack to be in place

> Stories 5.1 and 4.1 can be developed in parallel (no inter-dependency).
> Stories 6.1 and 1.3 can be developed in parallel once their respective prerequisites are complete.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `ShortenRequest` | `url: String` | `url` | Validated with `@NotBlank` + `@URL`; input to `POST /shorten` |
| `ShortenResponse` | `code: String`, `shortUrl: String` | `code`, `shortUrl` | Returned on 201 from `POST /shorten` |
| `ErrorResponse` | `error: String`, `message: String` | `error`, `message` | Returned on 400 / 404 / 410 responses |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | No (has default) | `jdbc:postgresql://localhost:5432/urlshortener` | Full JDBC URL for PostgreSQL connection |
| `DATABASE_USERNAME` | No (has default) | `postgres` | PostgreSQL username |
| `DATABASE_PASSWORD` | No (has default) | `postgres` | PostgreSQL password |
| `APP_BASE_URL` | No (has default) | `http://localhost:8080` | Base URL prepended to short codes in `ShortenResponse.shortUrl` |

### Database Schema

Table: `short_urls` — created by migration `V1__create_short_urls_table.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `code` | VARCHAR(6) | No | Unique (`uk_short_urls_code`) |
| `original_url` | TEXT | No | — |
| `created_at` | TIMESTAMP | No | — |
| `expires_at` | TIMESTAMP | No | — |

Additional constraints:
- `CONSTRAINT uk_short_urls_code UNIQUE (code)` — enforced at DB level as a safety net beyond the application-level collision-detection retry loop
- `CREATE INDEX idx_short_urls_code ON short_urls (code)` — optimizes `SELECT ... WHERE code = ?` lookups on every redirect request

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Short code collision under high load | L | M | Application-level retry loop up to `MAX_CODE_GENERATION_ATTEMPTS = 10`; DB-level `UNIQUE` constraint as final safety net; 62^6 ≈ 56.8B possible codes |
| PostgreSQL unavailable at startup | M | H | Spring Boot fails fast with a clear Flyway/Hibernate error; Docker Compose `depends_on` provides ordering; health endpoint shows DB status |
| Expired URL rows accumulate over time (table bloat) | M | L | Not required by PRD; a future `@Scheduled` cleanup job deleting rows where `expires_at < now()` would address this |
| `@URL` validation rejects valid but unusual URLs | M | M | `org.hibernate.validator.constraints.URL` accepts HTTP/HTTPS schemes; test with representative URL samples in integration tests |
| Testcontainers Docker daemon unavailable in CI | M | M | Ensure Docker is available in CI; document requirement; alternatively fall back to `@DataJpaTest` with H2 for unit-level DB tests |

---

## DEFINITION OF DONE

### For Each Story
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing (target: ≥80% line coverage on service layer)
- [ ] Integration tests passing
- [ ] No new linting or compiler warnings
- [ ] All acceptance criteria verified

### For the Release
- [ ] All stories complete
- [ ] `mvn test` passes with no failures
- [ ] Docker image builds with `docker build .` and starts correctly with `docker-compose up`
- [ ] `POST /shorten` and `GET /{code}` manually tested end-to-end
- [ ] 410 Gone response verified by inserting a row with past `expires_at` and calling `GET /{code}`
- [ ] Environment variables documented in README
- [ ] API contract documented (this plan serves as the reference)

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — `pom.xml` is the foundation; no code compiles without it
2. **Story 1.2** — Spring Boot entry point and `application.yml` / `AppProperties`; prerequisite for all stories
3. **Story 5.1** — Exception classes; no dependencies; needed by service layer
4. **Story 4.1** — DTOs; no dependencies; needed by controller and exception handler
5. **Story 2.1** — Flyway migration; schema must exist before Hibernate validates it
6. **Story 2.2** — `ShortUrl` JPA entity; depends on migration
7. **Story 2.3** — `ShortUrlRepository`; depends on entity
8. **Story 3.1** — `UrlShortenerService`; depends on repository, `AppProperties`, exceptions
9. **Story 5.2** — `GlobalExceptionHandler`; depends on exception classes and `ErrorResponse`
10. **Story 4.2** — `UrlShortenerController`; depends on service, DTOs, `AppProperties`
11. **Story 1.3** — Docker infrastructure; can be done once the app builds
12. **Story 6.1** — Unit tests for service; can start after Story 3.1
13. **Story 6.2** — Integration tests; require full stack

> Stories 5.1 and 4.1 can be implemented in parallel.
> Stories 6.1 and 1.3 can be implemented in parallel once their prerequisites are met.
