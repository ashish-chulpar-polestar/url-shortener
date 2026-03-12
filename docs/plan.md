# AGILE IMPLEMENTATION PLAN
**Project:** URL Shortener Service
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The URL Shortener Service is a stateless Spring Boot 3.x REST API that converts arbitrarily long URLs into unique 6-character alphanumeric codes stored in PostgreSQL. Each short code carries a 30-day TTL enforced both at the application layer (expiry check) and stored as a database column (`expires_at`). The two public endpoints are `POST /shorten` (create a code, return HTTP 201) and `GET /{code}` (redirect HTTP 302, or return HTTP 410 for expired / HTTP 404 for unknown). Schema migrations are managed by Flyway, connection pooling by HikariCP, and code generation uses `SecureRandom` with an application-level retry loop backed by a database unique constraint as the final collision guard. The delivery is a single runnable fat JAR containerised with Docker and exercised by unit and `@WebMvcTest` slice tests targeting ≥ 80 % line coverage on service and utility classes.

---

## TECHNICAL ANALYSIS

### Recommended Stack

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 17 (LTS) | Required by PRD; `java.time` API for UTC timestamps; modern records and sealed types available |
| Build | Maven 3.9 + `spring-boot-maven-plugin` | Produces runnable fat JAR via `./mvnw package`; Spring Boot parent BOM manages dependency versions |
| Framework | Spring Boot 3.3.6 (Spring MVC, not WebFlux) | PRD explicitly requires Spring MVC REST; auto-configuration minimises boilerplate |
| Database | PostgreSQL 15+ | PRD requirement; `BIGSERIAL`, `TIMESTAMPTZ`, `TEXT`, and `UNIQUE` constraint are all natively supported |
| ORM / Data Access | Spring Data JPA (Hibernate 6) | PRD-preferred; derived query methods eliminate boilerplate SQL for lookup and existence checks |
| Schema Migration | Flyway 10 (`flyway-core` + `flyway-database-postgresql`) | PRD requires version-controlled reproducible schema; Flyway 10 splits DB driver modules |
| Connection Pool | HikariCP (Spring Boot default) | PRD requirement; configured via `spring.datasource.hikari.*` properties |
| Validation | `spring-boot-starter-validation` (Jakarta Bean Validation 3) | Declarative `@NotBlank` on DTOs; triggers `MethodArgumentNotValidException` → HTTP 400 |
| Observability | Spring Boot Actuator | PRD requires `/actuator/health` and `/actuator/info`; health contributors for DataSource included automatically |
| Testing | JUnit 5, Mockito 5, `spring-boot-starter-test`, `@WebMvcTest` | Unit tests with `MockitoExtension`; controller slice tests without a real database |
| Containerisation | Docker multi-stage + `docker-compose` | PRD-recommended; multi-stage keeps runtime image small |

### Project Structure

```
url-shortener/
└── source/
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
    │   │   │       ├── InvalidUrlException.java
    │   │   │       ├── CodeGenerationException.java
    │   │   │       └── GlobalExceptionHandler.java
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       └── db/migration/
    │   │           └── V1__create_short_urls_table.sql
    │   └── test/
    │       ├── java/com/example/urlshortener/
    │       │   ├── service/
    │       │   │   ├── CodeGeneratorTest.java
    │       │   │   └── UrlShortenerServiceTest.java
    │       │   └── controller/
    │       │       └── UrlShortenerControllerTest.java
    │       └── resources/
    │           └── application-test.yml
    ├── Dockerfile
    ├── docker-compose.yml
    └── pom.xml
```

### Integration Points

- **PostgreSQL** — sole external dependency; connected via HikariCP JDBC pool; schema owned by Flyway; health checked via Spring Boot Actuator DataSource health contributor.
- **Flyway** — runs migrations on startup before the application context finishes initialising; no manual DDL step required.
- **Spring Boot Actuator** — exposes `/actuator/health` (liveness + datasource readiness) and `/actuator/info`; used by Docker health-check and CI pipeline.

### Technical Constraints

- `GET /{code}` p99 latency < 100 ms; `POST /shorten` p99 latency < 300 ms under normal load.
- All timestamps stored and compared in UTC using `java.time.OffsetDateTime` with `ZoneOffset.UTC`; never `java.util.Date` or `LocalDateTime`.
- `SecureRandom` (not `Random` or `Math.random()`) for code generation (NFR security constraint).
- No stack traces or SQL error messages in HTTP error responses.
- Stateless application tier — no `HttpSession`, no in-memory shared state — enabling horizontal scaling.
- Short code uniqueness enforced at application level (retry loop, up to `app.max-retries` attempts) and database level (`UNIQUE` constraint `uq_short_urls_code`).

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.urlshortener` | **Group ID:** `com.example` | **Artifact ID:** `url-shortener`

### Overview

The backend is a single Spring Boot application divided into seven vertical layers: project bootstrap (POM, main class), database migration, JPA entity and repository, typed configuration properties, the core service (code generation + URL validation + persistence + expiry checking), the REST controller with DTOs and exception handling, and a test suite covering unit and slice test scenarios. There is no frontend; the service is a pure JSON API plus an HTTP redirect endpoint. All layers are independent of external caches or message queues.

---

### Epic 1: Project Bootstrap

**Goal:** Create a compilable Spring Boot 3 skeleton with all required dependencies so that every subsequent layer can be built without revisiting the POM.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 1.1: Maven project setup and main application class

**As a** developer **I want** a Maven POM and a Spring Boot main class **so that** `./mvnw package` produces a runnable fat JAR with all required framework features on the classpath.

**Background for implementer:** Spring Boot 3.x requires Java 17+. The `spring-boot-starter-parent:3.3.6` BOM manages transitive dependency versions for all Spring starters, Hibernate, HikariCP, Flyway, and JUnit 5, so explicit `<version>` tags are not needed on managed artifacts. Flyway 10 split database-specific modules out of `flyway-core`; both `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` must be declared or Flyway will throw `FlywayException: No database found to handle jdbc:postgresql://...` at startup. The `spring-boot-maven-plugin` must be declared under `<build><plugins>` to repackage the plain JAR into a runnable fat JAR with `BOOT-INF/` layout.

**Acceptance Criteria:**
- [ ] `source/pom.xml` exists with `groupId` `com.example`, `artifactId` `url-shortener`, `version` `0.0.1-SNAPSHOT`, `packaging` `jar`
- [ ] Parent is `org.springframework.boot:spring-boot-starter-parent:3.3.6`
- [ ] All required starters declared: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, `spring-boot-starter-test` (test)
- [ ] `spring-boot-maven-plugin` declared under `<build><plugins>`
- [ ] Class `UrlShortenerApplication` exists with `@SpringBootApplication` and a `main` method
- [ ] `./mvnw package -DskipTests` produces `target/url-shortener-0.0.1-SNAPSHOT.jar`

**Tasks:**

**Task 1.1.a — Create Maven POM** — file: `source/pom.xml`

Create `source/pom.xml`. Set `<groupId>com.example</groupId>`, `<artifactId>url-shortener</artifactId>`, `<version>0.0.1-SNAPSHOT</version>`, `<packaging>jar</packaging>`. Declare `<parent>` with `groupId` `org.springframework.boot`, `artifactId` `spring-boot-starter-parent`, `version` `3.3.6`, `relativePath` empty. Add `<properties>` with `<java.version>17</java.version>`. Add the following `<dependencies>`: `org.springframework.boot:spring-boot-starter-web` (compile scope — omit `<scope>` tag); `org.springframework.boot:spring-boot-starter-data-jpa` (compile); `org.springframework.boot:spring-boot-starter-actuator` (compile); `org.springframework.boot:spring-boot-starter-validation` (compile); `org.postgresql:postgresql` with `<scope>runtime</scope>`; `org.flywaydb:flyway-core` (compile); `org.flywaydb:flyway-database-postgresql` (compile); `org.springframework.boot:spring-boot-starter-test` with `<scope>test</scope>`. Under `<build><plugins>`, declare `org.springframework.boot:spring-boot-maven-plugin` with no additional configuration.

**Task 1.1.b — Create main application class** — file: `source/src/main/java/com/example/urlshortener/UrlShortenerApplication.java`

Create class `UrlShortenerApplication` in package `com.example.urlshortener` annotated with `@SpringBootApplication`. Implement `public static void main(String[] args)` that calls `SpringApplication.run(UrlShortenerApplication.class, args)`. This is the sole entry point for the fat JAR; `@SpringBootApplication` enables component scan over `com.example.urlshortener` and all sub-packages, auto-configuration, and configuration property binding.

**Complexity:** S | **Dependencies:** None

---

### Epic 2: Database Layer

**Goal:** Establish the PostgreSQL schema via a Flyway migration, define the JPA entity mapping, and create the Spring Data repository interface.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Flyway database migration

**As a** developer **I want** a Flyway V1 migration that creates the `short_urls` table with all required columns, the unique constraint, and the lookup index **so that** the schema is reproducible, version-controlled, and correct on any fresh PostgreSQL database.

**Background for implementer:** Flyway discovers migrations automatically from `classpath:db/migration` when `spring.flyway.enabled=true` and `flyway-core` is on the classpath; no `@Configuration` class is needed. The file must follow the naming convention `V{version}__{description}.sql` — two underscores between version and description. The named unique constraint `uq_short_urls_code` is important because Spring Data JPA wraps the resulting `SQLException` (unique violation, SQLSTATE 23505) in a `DataIntegrityViolationException`, and the constraint name makes the log message unambiguous when debugging collisions. A separate `idx_short_urls_code` B-tree index is created after the `UNIQUE` constraint because while the `UNIQUE` constraint also creates an implicit index, making the lookup index explicit documents intent and is forward-compatible with read-replica query hints.

**Acceptance Criteria:**
- [ ] File `source/src/main/resources/db/migration/V1__create_short_urls_table.sql` exists
- [ ] `CREATE TABLE short_urls` with columns: `id BIGSERIAL PRIMARY KEY`, `code VARCHAR(6) NOT NULL`, `original_url TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `expires_at TIMESTAMPTZ NOT NULL`
- [ ] Constraint `CONSTRAINT uq_short_urls_code UNIQUE (code)` declared inline
- [ ] `CREATE INDEX idx_short_urls_code ON short_urls (code)` declared after table creation
- [ ] Application starts without Flyway errors against a fresh PostgreSQL database

**Tasks:**

**Task 2.1.a — Write V1 migration SQL** — file: `source/src/main/resources/db/migration/V1__create_short_urls_table.sql`

Write a SQL script containing exactly two statements. First, a `CREATE TABLE short_urls` statement with the following column definitions: `id BIGSERIAL PRIMARY KEY`, `code VARCHAR(6) NOT NULL`, `original_url TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `expires_at TIMESTAMPTZ NOT NULL`, and the inline table constraint `CONSTRAINT uq_short_urls_code UNIQUE (code)`. Second, `CREATE INDEX idx_short_urls_code ON short_urls (code)`. No `DROP TABLE IF EXISTS`, no `CREATE TABLE IF NOT EXISTS` — Flyway manages versioning and will never run this migration twice.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 2.2: ShortUrl JPA entity

**As a** developer **I want** a JPA entity `ShortUrl` mapped to the `short_urls` table **so that** Hibernate can read and write URL mapping rows with type-safe Java objects.

**Background for implementer:** Use `OffsetDateTime` (not `LocalDateTime`, not `java.util.Date`) for `createdAt` and `expiresAt` to preserve UTC offset information per the PRD constraint. Hibernate 6 maps `OffsetDateTime` to `TIMESTAMPTZ` natively without a custom `AttributeConverter`. The `@Column(columnDefinition = "TEXT")` on `originalUrl` tells Hibernate to use PostgreSQL's unbounded `TEXT` type rather than defaulting to `VARCHAR(255)`. Setting `spring.jpa.hibernate.ddl-auto=validate` means Hibernate validates the column types against the entity but never modifies the schema — Flyway owns DDL. Do not add `@PrePersist` lifecycle hooks for `createdAt`/`expiresAt`; these values will be set explicitly by the service layer so that unit tests can inject controlled timestamps without clock manipulation.

**Acceptance Criteria:**
- [ ] Class `ShortUrl` in `com.example.urlshortener.entity`, annotated `@Entity` and `@Table(name = "short_urls")`
- [ ] `private Long id` annotated `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
- [ ] `private String code` annotated `@Column(name = "code", nullable = false, length = 6)`
- [ ] `private String originalUrl` annotated `@Column(name = "original_url", nullable = false, columnDefinition = "TEXT")`
- [ ] `private OffsetDateTime createdAt` annotated `@Column(name = "created_at", nullable = false)`
- [ ] `private OffsetDateTime expiresAt` annotated `@Column(name = "expires_at", nullable = false)`
- [ ] Getters, setters, no-arg constructor, and all-arg constructor present

**Tasks:**

**Task 2.2.a — Create ShortUrl entity** — file: `source/src/main/java/com/example/urlshortener/entity/ShortUrl.java`

Create class `ShortUrl` in package `com.example.urlshortener.entity`. Annotate with `@Entity` and `@Table(name = "short_urls")`. Declare five fields: `private Long id` with `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)`; `private String code` with `@Column(name = "code", nullable = false, length = 6)`; `private String originalUrl` with `@Column(name = "original_url", nullable = false, columnDefinition = "TEXT")`; `private OffsetDateTime createdAt` with `@Column(name = "created_at", nullable = false)`; `private OffsetDateTime expiresAt` with `@Column(name = "expires_at", nullable = false)`. Generate getter and setter methods for all five fields following standard JavaBeans naming: `getId()`, `setId(Long id)`, `getCode()`, `setCode(String code)`, `getOriginalUrl()`, `setOriginalUrl(String originalUrl)`, `getCreatedAt()`, `setCreatedAt(OffsetDateTime createdAt)`, `getExpiresAt()`, `setExpiresAt(OffsetDateTime expiresAt)`. Add a public no-argument constructor (required by JPA) and a public all-argument constructor `ShortUrl(Long id, String code, String originalUrl, OffsetDateTime createdAt, OffsetDateTime expiresAt)` for convenience in test factories. The imports needed are `jakarta.persistence.*` and `java.time.OffsetDateTime`.

**Complexity:** S | **Dependencies:** Story 2.1

---

#### Story 2.3: ShortUrlRepository

**As a** developer **I want** a Spring Data JPA repository interface for `ShortUrl` **so that** the service layer can perform lookups and persistence without writing JPQL or native SQL.

**Acceptance Criteria:**
- [ ] Interface `ShortUrlRepository` in `com.example.urlshortener.repository` extends `JpaRepository<ShortUrl, Long>`
- [ ] Method `Optional<ShortUrl> findByCode(String code)` declared — Spring Data derives `SELECT s FROM ShortUrl s WHERE s.code = :code`
- [ ] Method `boolean existsByCode(String code)` declared — Spring Data derives `SELECT CASE WHEN count(s) > 0 THEN true ELSE false END FROM ShortUrl s WHERE s.code = :code`
- [ ] No `@Repository` annotation needed (inherited from `JpaRepository`)

**Tasks:**

**Task 2.3.a — Create ShortUrlRepository interface** — file: `source/src/main/java/com/example/urlshortener/repository/ShortUrlRepository.java`

Create interface `ShortUrlRepository` in package `com.example.urlshortener.repository` extending `JpaRepository<ShortUrl, Long>` where `ShortUrl` is `com.example.urlshortener.entity.ShortUrl`. Declare two additional methods: `Optional<ShortUrl> findByCode(String code)` — Spring Data automatically derives the query `SELECT s FROM ShortUrl s WHERE s.code = :code` from the method name at startup; `boolean existsByCode(String code)` — Spring Data derives a count-based existence check. Both methods must import `java.util.Optional`. No `@Repository` annotation is required because `JpaRepository` carries `@NoRepositoryBean` and Spring Boot's auto-configuration (`@EnableJpaRepositories`) scans the base package `com.example.urlshortener` and registers all sub-interface implementations automatically.

**Complexity:** S | **Dependencies:** Story 2.2

---

### Epic 3: Application Configuration

**Goal:** Bind all `app.*` configuration properties to a typed `@ConfigurationProperties` class and write the complete `application.yml` with every required key.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 3.1: AppProperties configuration class and application.yml

**As a** developer **I want** a `@ConfigurationProperties` bean `AppProperties` and a complete `application.yml` **so that** the service layer can read `app.base-url`, `app.expiry-days`, and `app.max-retries` in a type-safe, IDE-friendly, and test-overridable manner.

**Background for implementer:** `@ConfigurationProperties(prefix = "app")` binds all `app.*` keys from `application.yml` to the class fields via setter injection at startup. Annotating the class with `@Component` registers it as a Spring bean without a separate `@EnableConfigurationProperties` declaration on the main class, provided Spring Boot's component scan covers the `com.example.urlshortener.config` package. The `app.base-url` property defaults to an empty string (`${APP_BASE_URL:}`); when empty, `UrlShortenerController` derives the base URL at request time from `HttpServletRequest.getScheme()` + `Host` header, making the service work locally without any environment variable. The `spring.jpa.open-in-view=false` setting must be explicit to suppress the Spring Boot warning and avoid holding database connections open for the full HTTP request lifecycle. `management.endpoints.web.exposure.include=health,info` restricts Actuator exposure to only the two endpoints required by the PRD.

**Acceptance Criteria:**
- [ ] Class `AppProperties` in `com.example.urlshortener.config` annotated `@ConfigurationProperties(prefix = "app")` and `@Component`
- [ ] Field `private String baseUrl` (default `""`) with getter/setter; maps to `app.base-url`
- [ ] Field `private int expiryDays` (default `30`) with getter/setter; maps to `app.expiry-days`
- [ ] Field `private int maxRetries` (default `5`) with getter/setter; maps to `app.max-retries`
- [ ] `source/src/main/resources/application.yml` contains all required datasource, JPA, Flyway, app, management, and logging keys

**Tasks:**

**Task 3.1.a — Create AppProperties class** — file: `source/src/main/java/com/example/urlshortener/config/AppProperties.java`

Create class `AppProperties` in package `com.example.urlshortener.config`. Annotate with `@ConfigurationProperties(prefix = "app")`, `@Component`, and `@Validated`. Declare three fields: `private String baseUrl = ""` (maps to YAML key `app.base-url`); `private int expiryDays = 30` (maps to `app.expiry-days`); `private int maxRetries = 5` (maps to `app.max-retries`). Generate standard getter and setter methods: `getBaseUrl()`, `setBaseUrl(String baseUrl)`, `getExpiryDays()`, `setExpiryDays(int expiryDays)`, `getMaxRetries()`, `setMaxRetries(int maxRetries)`. The `@Validated` annotation enables JSR-303 validation of property values at application startup so that a misconfigured `app.expiry-days: -1` would fail fast if a `@Min` constraint were later added.

**Task 3.1.b — Configure application.yml** — file: `source/src/main/resources/application.yml`

Create `application.yml` with the following exact keys and values. Under `spring.datasource`: `url: ${SPRING_DATASOURCE_URL}`, `username: ${SPRING_DATASOURCE_USERNAME}`, `password: ${SPRING_DATASOURCE_PASSWORD}`. Under `spring.datasource.hikari`: `maximum-pool-size: 10`, `connection-timeout: 30000`, `idle-timeout: 600000`, `max-lifetime: 1800000`. Under `spring.jpa.hibernate`: `ddl-auto: validate`. Under `spring.jpa`: `open-in-view: false`. Under `spring.flyway`: `enabled: true`. Under `app`: `base-url: ${APP_BASE_URL:}` (empty default when environment variable not set), `expiry-days: 30`, `max-retries: 5`. Under `management.endpoints.web.exposure`: `include: health,info`. Under `server`: `port: 8080`. Under `logging.level.com.example.urlshortener`: `INFO`. These are the exact property keys consumed by Spring Boot auto-configuration, HikariCP, Flyway, and the `AppProperties` class.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 4: Core Service Logic

**Goal:** Implement the URL shortening business logic: URL format validation, secure code generation with collision retry, persistence, expiry checking, and redirect resolution.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 4.1: Custom exception classes

**As a** developer **I want** four dedicated exception classes covering not-found, expired, invalid-URL, and code-generation-failure conditions **so that** `GlobalExceptionHandler` can map each to a specific HTTP status code without `instanceof` branching in the controller.

**Acceptance Criteria:**
- [ ] `UrlNotFoundException` extends `RuntimeException`, single-arg `String message` constructor, in `com.example.urlshortener.exception`
- [ ] `UrlExpiredException` extends `RuntimeException`, single-arg `String message` constructor, same package
- [ ] `InvalidUrlException` extends `RuntimeException`, single-arg `String message` constructor, same package
- [ ] `CodeGenerationException` extends `RuntimeException`, single-arg `String message` constructor, same package

**Tasks:**

**Task 4.1.a — Create UrlNotFoundException** — file: `source/src/main/java/com/example/urlshortener/exception/UrlNotFoundException.java`

Create class `UrlNotFoundException` extending `RuntimeException` in package `com.example.urlshortener.exception`. Provide a single constructor with signature `public UrlNotFoundException(String message)` that calls `super(message)`. This exception is thrown by `UrlShortenerService.resolveCode(String code)` when `ShortUrlRepository.findByCode(code)` returns `Optional.empty()`. `GlobalExceptionHandler` maps it to HTTP 404 with body `{"error": "Not found"}`.

**Task 4.1.b — Create UrlExpiredException** — file: `source/src/main/java/com/example/urlshortener/exception/UrlExpiredException.java`

Create class `UrlExpiredException` extending `RuntimeException` in package `com.example.urlshortener.exception`. Provide a single constructor with signature `public UrlExpiredException(String message)` that calls `super(message)`. This exception is thrown by `UrlShortenerService.resolveCode(String code)` when the found `ShortUrl`'s `expiresAt` value is not after `OffsetDateTime.now(ZoneOffset.UTC)`. `GlobalExceptionHandler` maps it to HTTP 410 with body `{"error": "Link expired"}`.

**Task 4.1.c — Create InvalidUrlException** — file: `source/src/main/java/com/example/urlshortener/exception/InvalidUrlException.java`

Create class `InvalidUrlException` extending `RuntimeException` in package `com.example.urlshortener.exception`. Provide a single constructor with signature `public InvalidUrlException(String message)` that calls `super(message)`. This exception is thrown by `UrlShortenerService.validateUrl(String url)` when the URL is syntactically malformed or lacks an `http`/`https` scheme or a non-blank host. `GlobalExceptionHandler` maps it to HTTP 422 with body `{"error": "url is not a valid URL"}`.

**Task 4.1.d — Create CodeGenerationException** — file: `source/src/main/java/com/example/urlshortener/exception/CodeGenerationException.java`

Create class `CodeGenerationException` extending `RuntimeException` in package `com.example.urlshortener.exception`. Provide a single constructor with signature `public CodeGenerationException(String message)` that calls `super(message)`. This exception is thrown by `UrlShortenerService.shortenUrl(String url, String baseUrl)` when the retry loop exhausts all `appProperties.getMaxRetries()` attempts without producing a non-colliding code. `GlobalExceptionHandler` maps it to HTTP 500 with body `{"error": "Internal server error"}`.

**Complexity:** S | **Dependencies:** None

---

#### Story 4.2: URL shortener service

**As a** developer **I want** service class `UrlShortenerService` that encapsulates code generation, URL validation, persistence, and expiry-aware redirect resolution **so that** all business logic is isolated from the HTTP layer and is fully unit-testable.

**Background for implementer:** The character alphabet for code generation is the 62-character set `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789`, giving 62^6 ≈ 56.8 billion combinations. `SecureRandom.nextInt(int)` is used — not `Random` — per NFR-security constraint. Uniqueness is enforced in two layers: (1) the application retry loop catches `DataIntegrityViolationException` (thrown by Spring Data JPA when PostgreSQL returns SQLSTATE 23505 unique-violation on `uq_short_urls_code`) and re-generates a new code; (2) if all `maxRetries` attempts fail, `CodeGenerationException` is thrown and results in HTTP 500. URL validation uses `java.net.URI.create(url)` inside a try-catch for `IllegalArgumentException`; after parsing, the scheme must be either `"http"` or `"https"` and the host must be non-null and non-blank, otherwise `InvalidUrlException` is thrown (HTTP 422). The expiry boundary condition is `!shortUrl.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))`, meaning any `expiresAt` value equal to or earlier than now is expired (FR-20 requirement).

**Acceptance Criteria:**
- [ ] Class `UrlShortenerService` in `com.example.urlshortener.service`, annotated `@Service`
- [ ] Constructor-injected `ShortUrlRepository shortUrlRepository` and `AppProperties appProperties`
- [ ] `private static final String ALPHABET` = `"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"` (exactly 62 chars)
- [ ] `private static final int CODE_LENGTH = 6`
- [ ] `private final SecureRandom secureRandom = new SecureRandom()`
- [ ] `public String generateCode()` — returns 6-char string from ALPHABET using SecureRandom
- [ ] `@Transactional public ShortenResponse shortenUrl(String url, String baseUrl)` — validates, generates code with retry, persists, returns response
- [ ] `public String resolveCode(String code)` — looks up, checks expiry, returns originalUrl or throws
- [ ] `private void validateUrl(String url)` — URI.create + scheme/host checks, throws InvalidUrlException
- [ ] Logger declared at class level; INFO/WARN/ERROR statements as specified below

**Tasks:**

**Task 4.2.a — Implement UrlShortenerService** — file: `source/src/main/java/com/example/urlshortener/service/UrlShortenerService.java`

Create class `UrlShortenerService` in package `com.example.urlshortener.service` annotated with `@Service`. Inject dependencies via a single constructor: `public UrlShortenerService(ShortUrlRepository shortUrlRepository, AppProperties appProperties)` storing both parameters as `private final` fields. Declare class-level constants: `private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"` (exactly 62 characters); `private static final int CODE_LENGTH = 6`. Declare `private final SecureRandom secureRandom = new SecureRandom()`. Declare logger: `private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class)`.

Implement `public String generateCode()`: instantiate `StringBuilder sb = new StringBuilder(CODE_LENGTH)`; loop `CODE_LENGTH` times calling `sb.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())))` each iteration; return `sb.toString()`.

Implement `private void validateUrl(String url)`: wrap `URI.create(url)` in a try-catch for `IllegalArgumentException`; if the exception is caught, throw `new InvalidUrlException("url is not a valid URL")`; after parsing, retrieve `uri.getScheme()` — if it is null or neither `"http"` nor `"https"` throw `new InvalidUrlException("url is not a valid URL")`; retrieve `uri.getHost()` — if null or blank throw `new InvalidUrlException("url is not a valid URL")`.

Implement `@Transactional public ShortenResponse shortenUrl(String url, String baseUrl)`: call `validateUrl(url)` first; then enter a `for (int attempt = 0; attempt < appProperties.getMaxRetries(); attempt++)` loop; inside the loop, call `String candidate = generateCode()`; build a new `ShortUrl` entity setting `code = candidate`, `originalUrl = url`, `createdAt = OffsetDateTime.now(ZoneOffset.UTC)`, `expiresAt = createdAt.plusDays(appProperties.getExpiryDays())`; attempt `shortUrlRepository.save(entity)` inside a try-catch for `org.springframework.dao.DataIntegrityViolationException`; if caught, log at WARN: `log.warn("Code collision detected on attempt={} code={}", attempt, candidate)` and `continue` the loop; if save succeeds, log at INFO: `log.info("Short URL created code={} originalUrl={}", candidate, url)`; build and return `new ShortenResponse(candidate, baseUrl + "/" + candidate)`; after the loop exits without returning, log at ERROR: `log.error("Failed to generate unique code after maxRetries={}", appProperties.getMaxRetries())`; throw `new CodeGenerationException("Could not generate a unique short code after " + appProperties.getMaxRetries() + " attempts")`.

Implement `public String resolveCode(String code)`: call `shortUrlRepository.findByCode(code)`; if the Optional is empty, log at WARN: `log.warn("Short URL not found code={}", code)`; throw `new UrlNotFoundException("Not found")`; if present, get the `ShortUrl` entity; evaluate `!shortUrl.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))`; if true, log at WARN: `log.warn("Short URL expired code={} expiresAt={}", code, shortUrl.getExpiresAt())`; throw `new UrlExpiredException("Link expired")`; otherwise, log at INFO: `log.info("Redirecting code={} to originalUrl={}", code, shortUrl.getOriginalUrl())`; return `shortUrl.getOriginalUrl()`.

**Complexity:** L | **Dependencies:** Stories 2.3, 3.1, 4.1

---

### Epic 5: REST API Layer

**Goal:** Expose `POST /shorten` and `GET /{code}` via a Spring MVC controller, provide all required DTOs, and centralise error handling in a `@RestControllerAdvice` class.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 5.1: Request/Response DTOs

**As a** developer **I want** DTO classes `ShortenRequest`, `ShortenResponse`, and `ErrorResponse` **so that** the controller can deserialise incoming JSON and serialise outgoing JSON without coupling to the JPA entity.

**Acceptance Criteria:**
- [ ] `ShortenRequest` in `com.example.urlshortener.dto` — field `String url` with `@NotBlank(message = "url is required")`; no-arg constructor; getter and setter
- [ ] `ShortenResponse` in same package — fields `String code`, `String shortUrl`; no-arg constructor, all-arg constructor, getters and setters
- [ ] `ErrorResponse` in same package — field `String error`; no-arg constructor, all-arg constructor `ErrorResponse(String error)`, getter and setter

**Tasks:**

**Task 5.1.a — Create ShortenRequest DTO** — file: `source/src/main/java/com/example/urlshortener/dto/ShortenRequest.java`

Create class `ShortenRequest` in package `com.example.urlshortener.dto`. Declare `private String url` annotated with `@NotBlank(message = "url is required")` (import `jakarta.validation.constraints.NotBlank`). Provide a public no-argument constructor. Provide getter `public String getUrl()` and setter `public void setUrl(String url)`. Jackson deserialises the JSON field `"url"` into this field via the setter; the `@NotBlank` constraint triggers `MethodArgumentNotValidException` — handled by `GlobalExceptionHandler` as HTTP 400 — when `url` is null, empty string `""`, or whitespace-only. The `message` value `"url is required"` is the exact string returned in the error response body per the PRD API contract (Appendix A).

**Task 5.1.b — Create ShortenResponse DTO** — file: `source/src/main/java/com/example/urlshortener/dto/ShortenResponse.java`

Create class `ShortenResponse` in package `com.example.urlshortener.dto`. Declare fields `private String code` and `private String shortUrl`. Provide a public no-argument constructor, an all-argument constructor `public ShortenResponse(String code, String shortUrl)` assigning both fields, getters `getCode()` and `getShortUrl()`, and setters `setCode(String code)` and `setShortUrl(String shortUrl)`. Jackson serialises this to `{"code":"...","shortUrl":"..."}`. The field name `shortUrl` maps to the JSON key `shortUrl` (camelCase preserved) per the PRD API contract response shape `{ "code": "abc123", "shortUrl": "http://host/abc123" }`.

**Task 5.1.c — Create ErrorResponse DTO** — file: `source/src/main/java/com/example/urlshortener/dto/ErrorResponse.java`

Create class `ErrorResponse` in package `com.example.urlshortener.dto`. Declare field `private String error`. Provide a public no-argument constructor, an all-argument constructor `public ErrorResponse(String error)` assigning the field, getter `getError()`, and setter `setError(String error)`. Jackson serialises this to `{"error":"..."}`. All error handler methods in `GlobalExceptionHandler` return `new ErrorResponse("<message>")`.

**Complexity:** S | **Dependencies:** None

---

#### Story 5.2: Global exception handler

**As a** developer **I want** a `@RestControllerAdvice` class `GlobalExceptionHandler` that maps all application exceptions to the correct HTTP status and JSON error body **so that** no stack trace, SQL error, or internal class name is ever returned to clients.

**Background for implementer:** `@RestControllerAdvice` combines `@ControllerAdvice` (applies to all `@Controller` classes) with `@ResponseBody` (all handler return values are serialised as JSON), so each `@ExceptionHandler` method can return an `ErrorResponse` directly without wrapping in `ResponseEntity`. `MethodArgumentNotValidException` is thrown by Spring MVC's argument resolution when `@Valid` on a `@RequestBody` parameter fails; the first field error's `getDefaultMessage()` contains the `@NotBlank` `message` attribute `"url is required"`. `HttpMessageNotReadableException` is thrown when the request body is absent or is not parseable JSON. The ordering of `@ExceptionHandler` methods does not matter because each is bound to a specific exception type; the generic `Exception.class` handler is a catch-all and will only fire for types not matched by a more specific handler.

**Acceptance Criteria:**
- [ ] Class `GlobalExceptionHandler` in `com.example.urlshortener.exception`, annotated `@RestControllerAdvice`
- [ ] `UrlNotFoundException` → 404, body `{"error": "Not found"}`
- [ ] `UrlExpiredException` → 410, body `{"error": "Link expired"}`
- [ ] `InvalidUrlException` → 422, body `{"error": "url is not a valid URL"}`
- [ ] `MethodArgumentNotValidException` → 400, body `{"error": "<first field error default message>"}`
- [ ] `HttpMessageNotReadableException` → 400, body `{"error": "Request body is missing or malformed"}`
- [ ] `CodeGenerationException` → 500, body `{"error": "Internal server error"}`
- [ ] Generic `Exception` → 500, body `{"error": "Internal server error"}`
- [ ] Logger declared; WARN logged for 4xx handlers, ERROR for 5xx handlers

**Tasks:**

**Task 5.2.a — Implement GlobalExceptionHandler** — file: `source/src/main/java/com/example/urlshortener/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in package `com.example.urlshortener.exception` annotated with `@RestControllerAdvice`. Declare logger: `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`.

Implement `@ExceptionHandler(UrlNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND) public ErrorResponse handleNotFound(UrlNotFoundException ex)`: log `log.warn("URL not found: {}", ex.getMessage())`; return `new ErrorResponse("Not found")`.

Implement `@ExceptionHandler(UrlExpiredException.class) @ResponseStatus(HttpStatus.GONE) public ErrorResponse handleExpired(UrlExpiredException ex)`: log `log.warn("URL expired: {}", ex.getMessage())`; return `new ErrorResponse("Link expired")`.

Implement `@ExceptionHandler(InvalidUrlException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) public ErrorResponse handleInvalidUrl(InvalidUrlException ex)`: log `log.warn("Invalid URL: {}", ex.getMessage())`; return `new ErrorResponse("url is not a valid URL")`.

Implement `@ExceptionHandler(MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public ErrorResponse handleValidation(MethodArgumentNotValidException ex)`: extract `String message = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage()`; log `log.warn("Validation error: {}", message)`; return `new ErrorResponse(message)`.

Implement `@ExceptionHandler(HttpMessageNotReadableException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public ErrorResponse handleUnreadable(HttpMessageNotReadableException ex)`: log `log.warn("Unreadable message: {}", ex.getMessage())`; return `new ErrorResponse("Request body is missing or malformed")`. Import `org.springframework.http.converter.HttpMessageNotReadableException`.

Implement `@ExceptionHandler(CodeGenerationException.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) public ErrorResponse handleCodeGeneration(CodeGenerationException ex)`: log `log.error("Code generation failure: {}", ex.getMessage())`; return `new ErrorResponse("Internal server error")`.

Implement `@ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) public ErrorResponse handleGeneric(Exception ex)`: log `log.error("Unexpected error", ex)`; return `new ErrorResponse("Internal server error")`.

**Complexity:** S | **Dependencies:** Stories 4.1, 5.1

---

#### Story 5.3: URL shortener REST controller

**As a** developer **I want** controller class `UrlShortenerController` with `POST /shorten` and `GET /{code}` endpoints **so that** API consumers can shorten URLs and end users are redirected to original destinations via HTTP.

**Background for implementer:** `POST /shorten` must return HTTP 201 Created (not 200 OK) per FR-08. `GET /{code}` returns `ResponseEntity<Void>` with HTTP 302 and a `Location` header — `ResponseEntity<Void>` (rather than `ResponseEntity<String>`) is correct because the redirect response body must be empty per FR-14. The base URL for the `shortUrl` response field is derived at request time: if `appProperties.getBaseUrl()` is non-blank, use it; otherwise construct it from `httpRequest.getScheme() + "://" + httpRequest.getHeader("Host")`. This fallback makes the service work correctly in local development without setting the `APP_BASE_URL` environment variable. The `@Valid` annotation on `@RequestBody ShortenRequest` activates Jakarta Bean Validation, causing `MethodArgumentNotValidException` to be thrown (and handled by `GlobalExceptionHandler`) when `url` is blank.

**Acceptance Criteria:**
- [ ] Class `UrlShortenerController` in `com.example.urlshortener.controller`, annotated `@RestController`
- [ ] `@PostMapping("/shorten")` method `shorten` returns `ResponseEntity<ShortenResponse>` with 201 on success
- [ ] `@GetMapping("/{code}")` method `redirect` returns `ResponseEntity<Void>` with 302 and `Location` header on success
- [ ] `@Valid` present on `@RequestBody ShortenRequest` parameter of `shorten`
- [ ] Logger declared; INFO logged on entry to each endpoint

**Tasks:**

**Task 5.3.a — Implement UrlShortenerController** — file: `source/src/main/java/com/example/urlshortener/controller/UrlShortenerController.java`

Create class `UrlShortenerController` in package `com.example.urlshortener.controller` annotated with `@RestController`. Inject `UrlShortenerService urlShortenerService` and `AppProperties appProperties` via a single constructor `public UrlShortenerController(UrlShortenerService urlShortenerService, AppProperties appProperties)`, storing both as `private final` fields. Declare logger: `private static final Logger log = LoggerFactory.getLogger(UrlShortenerController.class)`.

Implement `@PostMapping("/shorten") public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request, HttpServletRequest httpRequest)`: derive `String baseUrl` — use `appProperties.getBaseUrl()` if non-blank, otherwise construct `httpRequest.getScheme() + "://" + httpRequest.getHeader("Host")`; log at INFO: `log.info("Shorten request received url={}", request.getUrl())`; call `ShortenResponse response = urlShortenerService.shortenUrl(request.getUrl(), baseUrl)`; return `ResponseEntity.status(HttpStatus.CREATED).body(response)`. Import `jakarta.servlet.http.HttpServletRequest`.

Implement `@GetMapping("/{code}") public ResponseEntity<Void> redirect(@PathVariable String code)`: log at INFO: `log.info("Redirect request received code={}", code)`; call `String originalUrl = urlShortenerService.resolveCode(code)`; return `ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, originalUrl).build()`. Import `org.springframework.http.HttpHeaders`.

**Complexity:** S | **Dependencies:** Stories 4.2, 5.1, 5.2

---

### Epic 6: Testing

**Goal:** Implement unit tests for code generation (FR-19), expiry logic (FR-20), and `@WebMvcTest` slice tests for the controller covering all HTTP scenarios (FR-21).
**Priority:** High | **Estimated Complexity:** M

---

#### Story 6.1: Unit tests for code generation

**As a** developer **I want** unit tests on `UrlShortenerService.generateCode()` **so that** FR-19 requirements (length, charset, uniqueness) are verified independently of the database or HTTP layer.

**Background for implementer:** These tests exercise `generateCode()` directly on a real `UrlShortenerService` instance with Mockito-mocked constructor dependencies (`ShortUrlRepository` and `AppProperties`). `SecureRandom` is not seeded — the goal is structural validation (length, alphabet) and statistical uniqueness. Testing that 10 successive calls produce at least 2 distinct values has an astronomically low false-positive rate (probability all 10 match = (1/62^6)^9 ≈ 10^-108).

**Acceptance Criteria:**
- [ ] Test class `CodeGeneratorTest` in `com.example.urlshortener.service` under `source/src/test/java/`
- [ ] Test `generatedCode_hasLengthSix` — asserts `code.length() == 6`
- [ ] Test `generatedCode_containsOnlyAlphanumericCharacters` — asserts `code.matches("[A-Za-z0-9]{6}")`
- [ ] Test `generateCode_producesDistinctValues` — asserts set of 10 results has size > 1

**Tasks:**

**Task 6.1.a — Implement CodeGeneratorTest** — file: `source/src/test/java/com/example/urlshortener/service/CodeGeneratorTest.java`

Create test class `CodeGeneratorTest` in package `com.example.urlshortener.service` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock ShortUrlRepository shortUrlRepository` and `@Mock AppProperties appProperties`. Declare `@InjectMocks UrlShortenerService urlShortenerService`. Add `@BeforeEach void setUp()` that stubs `Mockito.when(appProperties.getMaxRetries()).thenReturn(5)` and `Mockito.when(appProperties.getExpiryDays()).thenReturn(30)`.

Implement `@Test void generatedCode_hasLengthSix()`: call `String code = urlShortenerService.generateCode()`; assert `Assertions.assertEquals(6, code.length(), "Generated code length must be exactly 6")`.

Implement `@Test void generatedCode_containsOnlyAlphanumericCharacters()`: call `String code = urlShortenerService.generateCode()`; assert `Assertions.assertTrue(code.matches("[A-Za-z0-9]{6}"), "Code must only contain [A-Za-z0-9] characters but was: " + code)`.

Implement `@Test void generateCode_producesDistinctValues()`: create `Set<String> codes = new HashSet<>()`; call `urlShortenerService.generateCode()` 10 times adding each result to `codes`; assert `Assertions.assertTrue(codes.size() > 1, "Expected at least 2 distinct codes in 10 calls but all were identical")`.

**Complexity:** S | **Dependencies:** Story 4.2

---

#### Story 6.2: Unit tests for expiry logic

**As a** developer **I want** unit tests on `UrlShortenerService.resolveCode()` covering the expiry boundary conditions **so that** FR-20 requirements are verified: active codes pass, expired codes throw `UrlExpiredException`, and the not-found path throws `UrlNotFoundException`.

**Background for implementer:** These tests mock `ShortUrlRepository.findByCode(String)` to return a pre-built `ShortUrl` entity with a controlled `expiresAt` value. This decouples the expiry logic test from the database and from `Clock` injection. The "exactly equal to now" boundary condition from FR-20 is approximated by setting `expiresAt = OffsetDateTime.now(ZoneOffset.UTC).minusNanos(1)`, which reliably represents "just expired" without the race condition inherent in testing exact millisecond equality; this exercises the same code path as exact equality because the condition is `!expiresAt.isAfter(now)`.

**Acceptance Criteria:**
- [ ] Test class `UrlShortenerServiceTest` in `com.example.urlshortener.service` under `source/src/test/java/`
- [ ] Test `resolveCode_returnsOriginalUrl_whenNotExpired` — future `expiresAt` → returns `"https://example.com/some/long/path"`
- [ ] Test `resolveCode_throwsUrlExpiredException_whenExpired` — past `expiresAt` (`now - 1 day`) → throws `UrlExpiredException`
- [ ] Test `resolveCode_throwsUrlExpiredException_whenExpiresAtIsInThePastByOneNanosecond` — `expiresAt = now - 1 nanosecond` → throws `UrlExpiredException`
- [ ] Test `resolveCode_throwsUrlNotFoundException_whenCodeNotFound` — `Optional.empty()` → throws `UrlNotFoundException`

**Tasks:**

**Task 6.2.a — Implement UrlShortenerServiceTest** — file: `source/src/test/java/com/example/urlshortener/service/UrlShortenerServiceTest.java`

Create test class `UrlShortenerServiceTest` in package `com.example.urlshortener.service` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock ShortUrlRepository shortUrlRepository`, `@Mock AppProperties appProperties`, `@InjectMocks UrlShortenerService urlShortenerService`. Add `@BeforeEach void setUp()` stubbing `Mockito.when(appProperties.getMaxRetries()).thenReturn(5)` and `Mockito.when(appProperties.getExpiryDays()).thenReturn(30)`.

Implement `@Test void resolveCode_returnsOriginalUrl_whenNotExpired()`: create `ShortUrl entity = new ShortUrl()`; call `entity.setCode("abc123")`, `entity.setOriginalUrl("https://example.com/some/long/path")`, `entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))`, `entity.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(29))`; stub `Mockito.when(shortUrlRepository.findByCode("abc123")).thenReturn(Optional.of(entity))`; call `String result = urlShortenerService.resolveCode("abc123")`; assert `Assertions.assertEquals("https://example.com/some/long/path", result)`.

Implement `@Test void resolveCode_throwsUrlExpiredException_whenExpired()`: create `ShortUrl entity = new ShortUrl()`; set `entity.setCode("expired")`, `entity.setOriginalUrl("https://example.com/old")`, `entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(31))`, `entity.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))`; stub `Mockito.when(shortUrlRepository.findByCode("expired")).thenReturn(Optional.of(entity))`; assert `Assertions.assertThrows(UrlExpiredException.class, () -> urlShortenerService.resolveCode("expired"))`.

Implement `@Test void resolveCode_throwsUrlExpiredException_whenExpiresAtIsInThePastByOneNanosecond()`: create `ShortUrl entity = new ShortUrl()`; set `entity.setCode("almostexpired")`, `entity.setOriginalUrl("https://example.com/boundary")`, `entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(30))`, `entity.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusNanos(1))`; stub `Mockito.when(shortUrlRepository.findByCode("almostexpired")).thenReturn(Optional.of(entity))`; assert `Assertions.assertThrows(UrlExpiredException.class, () -> urlShortenerService.resolveCode("almostexpired"))`.

Implement `@Test void resolveCode_throwsUrlNotFoundException_whenCodeNotFound()`: stub `Mockito.when(shortUrlRepository.findByCode("unknown")).thenReturn(Optional.empty())`; assert `Assertions.assertThrows(UrlNotFoundException.class, () -> urlShortenerService.resolveCode("unknown"))`.

**Complexity:** S | **Dependencies:** Story 4.2

---

#### Story 6.3: Controller slice tests

**As a** developer **I want** `@WebMvcTest` slice tests for `UrlShortenerController` that cover all six HTTP scenarios without a database **so that** the HTTP interface contract is verified at the controller + exception-handler level.

**Background for implementer:** `@WebMvcTest(UrlShortenerController.class)` loads only the Spring MVC layer: the specified controller, all `@RestControllerAdvice` classes in the same application context, Jackson message converters, and `HandlerMethodArgumentResolver` implementations. It does NOT load JPA repositories, datasource, or Flyway — making it fast and database-free. `@MockBean UrlShortenerService` replaces the real service with a Mockito mock. `@MockBean AppProperties` is required because `UrlShortenerController` injects it; without it, the application context fails to start with "No qualifying bean of type AppProperties". The `MockMvc` bean is auto-configured by `@WebMvcTest` and injected via `@Autowired`.

**Acceptance Criteria:**
- [ ] Test class `UrlShortenerControllerTest` in `com.example.urlshortener.controller` under `source/src/test/java/`
- [ ] `POST /shorten` happy path → 201, `$.code` = `"abc123"`, `$.shortUrl` = `"http://localhost:8080/abc123"`
- [ ] `POST /shorten` blank URL → 400, `$.error` = `"url is required"`
- [ ] `POST /shorten` malformed URL → 422, `$.error` = `"url is not a valid URL"`
- [ ] `GET /abc123` valid → 302, `Location` header = `"https://example.com/target"`
- [ ] `GET /unknown` not found → 404, `$.error` = `"Not found"`
- [ ] `GET /expired` expired → 410, `$.error` = `"Link expired"`

**Tasks:**

**Task 6.3.a — Implement UrlShortenerControllerTest** — file: `source/src/test/java/com/example/urlshortener/controller/UrlShortenerControllerTest.java`

Create test class `UrlShortenerControllerTest` in package `com.example.urlshortener.controller` annotated with `@WebMvcTest(UrlShortenerController.class)`. Declare `@Autowired MockMvc mockMvc`, `@MockBean UrlShortenerService urlShortenerService`, `@MockBean AppProperties appProperties`. In `@BeforeEach void setUp()`, configure `Mockito.when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080")`.

Implement `@Test void shorten_happyPath_returns201()`: stub `Mockito.when(urlShortenerService.shortenUrl("https://example.com/long", "http://localhost:8080")).thenReturn(new ShortenResponse("abc123", "http://localhost:8080/abc123"))`; perform `mockMvc.perform(MockMvcRequestBuilders.post("/shorten").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/long\"}"))` and chain `.andExpect(MockMvcResultMatchers.status().isCreated())`, `.andExpect(MockMvcResultMatchers.jsonPath("$.code").value("abc123"))`, `.andExpect(MockMvcResultMatchers.jsonPath("$.shortUrl").value("http://localhost:8080/abc123"))`.

Implement `@Test void shorten_blankUrl_returns400()`: perform `mockMvc.perform(MockMvcRequestBuilders.post("/shorten").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))` and chain `.andExpect(MockMvcResultMatchers.status().isBadRequest())`, `.andExpect(MockMvcResultMatchers.jsonPath("$.error").value("url is required"))`.

Implement `@Test void shorten_malformedUrl_returns422()`: stub `Mockito.doThrow(new InvalidUrlException("url is not a valid URL")).when(urlShortenerService).shortenUrl(org.mockito.ArgumentMatchers.eq("not-a-url"), org.mockito.ArgumentMatchers.any())`; perform `mockMvc.perform(MockMvcRequestBuilders.post("/shorten").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"not-a-url\"}"))` and chain `.andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())`, `.andExpect(MockMvcResultMatchers.jsonPath("$.error").value("url is not a valid URL"))`.

Implement `@Test void redirect_validCode_returns302()`: stub `Mockito.when(urlShortenerService.resolveCode("abc123")).thenReturn("https://example.com/target")`; perform `mockMvc.perform(MockMvcRequestBuilders.get("/abc123"))` and chain `.andExpect(MockMvcResultMatchers.status().isFound())`, `.andExpect(MockMvcResultMatchers.header().string(org.springframework.http.HttpHeaders.LOCATION, "https://example.com/target"))`.

Implement `@Test void redirect_unknownCode_returns404()`: stub `Mockito.when(urlShortenerService.resolveCode("unknown")).thenThrow(new UrlNotFoundException("Not found"))`; perform `mockMvc.perform(MockMvcRequestBuilders.get("/unknown"))` and chain `.andExpect(MockMvcResultMatchers.status().isNotFound())`, `.andExpect(MockMvcResultMatchers.jsonPath("$.error").value("Not found"))`.

Implement `@Test void redirect_expiredCode_returns410()`: stub `Mockito.when(urlShortenerService.resolveCode("expired")).thenThrow(new UrlExpiredException("Link expired"))`; perform `mockMvc.perform(MockMvcRequestBuilders.get("/expired"))` and chain `.andExpect(MockMvcResultMatchers.status().isGone())`, `.andExpect(MockMvcResultMatchers.jsonPath("$.error").value("Link expired"))`.

**Complexity:** M | **Dependencies:** Stories 5.2, 5.3

---

### Epic 7: Containerisation

**Goal:** Provide a multi-stage `Dockerfile` and a `docker-compose.yml` so the full service (application + PostgreSQL) can be started locally with `docker compose up`.
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 7.1: Dockerfile

**As a** developer **I want** a multi-stage `Dockerfile` **so that** the application can be built and run in a container on any machine with Docker, without a local JDK or Maven installation.

**Background for implementer:** A two-stage build (builder + runtime) keeps the final image small. Stage 1 copies `pom.xml`, `.mvn/`, and `mvnw` before copying `src/` so that Docker's layer cache reuses the `dependency:go-offline` layer when only source files change. Stage 2 uses `eclipse-temurin:17-jre-alpine` (JRE-only, Alpine-based) which is approximately 100 MB smaller than a JDK image. The JAR artifact name `url-shortener-0.0.1-SNAPSHOT.jar` matches the `<artifactId>-<version>.jar` pattern set in `pom.xml`.

**Acceptance Criteria:**
- [ ] `source/Dockerfile` uses two `FROM` stages: `eclipse-temurin:17-jdk AS builder` and `eclipse-temurin:17-jre-alpine AS runtime`
- [ ] Builder runs `./mvnw dependency:go-offline -B` then `./mvnw package -DskipTests -B`
- [ ] Runtime copies `--from=builder /app/target/url-shortener-0.0.1-SNAPSHOT.jar app.jar`
- [ ] `EXPOSE 8080` and `ENTRYPOINT ["java", "-jar", "app.jar"]` declared in runtime stage

**Tasks:**

**Task 7.1.a — Create Dockerfile** — file: `source/Dockerfile`

Create `source/Dockerfile` with two stages. Stage 1: `FROM eclipse-temurin:17-jdk AS builder`; `WORKDIR /app`; `COPY pom.xml ./`; `COPY .mvn .mvn`; `COPY mvnw ./`; `RUN chmod +x mvnw && ./mvnw dependency:go-offline -B` (caches Maven dependencies as a separate layer); `COPY src src`; `RUN ./mvnw package -DskipTests -B`. Stage 2: `FROM eclipse-temurin:17-jre-alpine AS runtime`; `WORKDIR /app`; `COPY --from=builder /app/target/url-shortener-0.0.1-SNAPSHOT.jar app.jar`; `EXPOSE 8080`; `ENTRYPOINT ["java", "-jar", "app.jar"]`. No `USER` directive is required for initial delivery but can be added later for security hardening.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 7.2: docker-compose.yml

**As a** developer **I want** a `docker-compose.yml` defining `postgres` and `app` services **so that** `docker compose up` starts a complete working environment locally.

**Acceptance Criteria:**
- [ ] `source/docker-compose.yml` defines services `postgres` and `app`
- [ ] `postgres` uses image `postgres:15-alpine`, sets `POSTGRES_DB: urlshortener`, `POSTGRES_USER: urlshortener`, `POSTGRES_PASSWORD: secret`, maps port `5432:5432`, includes healthcheck using `pg_isready -U urlshortener`
- [ ] `app` builds from `./Dockerfile`, sets `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/urlshortener`, `SPRING_DATASOURCE_USERNAME: urlshortener`, `SPRING_DATASOURCE_PASSWORD: secret`, `APP_BASE_URL: http://localhost:8080`, maps port `8080:8080`
- [ ] `app` has `depends_on.postgres.condition: service_healthy`

**Tasks:**

**Task 7.2.a — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create `source/docker-compose.yml` with `version: "3.9"`. Define service `postgres` with: `image: postgres:15-alpine`; `environment` block containing `POSTGRES_DB: urlshortener`, `POSTGRES_USER: urlshortener`, `POSTGRES_PASSWORD: secret`; `ports: - "5432:5432"`; `healthcheck` block with `test: ["CMD-SHELL", "pg_isready -U urlshortener"]`, `interval: 10s`, `timeout: 5s`, `retries: 5`. Define service `app` with: `build: ./`; `environment` block containing `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/urlshortener`, `SPRING_DATASOURCE_USERNAME: urlshortener`, `SPRING_DATASOURCE_PASSWORD: secret`, `APP_BASE_URL: http://localhost:8080`; `ports: - "8080:8080"`; `depends_on` block with `postgres: condition: service_healthy`.

**Complexity:** S | **Dependencies:** Story 7.1

---

### Backend API Contracts

```
POST /shorten

Request Headers:
  Content-Type: application/json

Request Body:
  url   String   Required   Must be non-blank (HTTP 400 if blank) and syntactically valid
                             with scheme http or https and a non-blank host (HTTP 422 if not)

Success Response — 201 Created:
  code       String   6-character alphanumeric short code (matches [A-Za-z0-9]{6})
  shortUrl   String   Fully qualified short URL: "<scheme>://<host>/<code>"

Error Response — 400 Bad Request (missing or blank url):
  error   String   "url is required"

Error Response — 422 Unprocessable Entity (malformed url):
  error   String   "url is not a valid URL"

Error Response — 500 Internal Server Error (code generation exhausted):
  error   String   "Internal server error"
```

```
GET /{code}

Path Parameters:
  code   String   Required   6-character alphanumeric short code

Success Response — 302 Found (valid, non-expired code):
  Header: Location: <original_url>
  Body: empty

Error Response — 404 Not Found (unknown code):
  error   String   "Not found"

Error Response — 410 Gone (expired code):
  error   String   "Link expired"
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | `GET /{code}` p99 < 100 ms; `POST /shorten` p99 < 300 ms; minimum 200 concurrent requests supported |
| Logging | SLF4J + Logback (Spring Boot default); format: timestamp, level, logger, message; parameterised fields (not string concatenation); MDC not required for initial delivery; log level `INFO` for `com.example.urlshortener` package |
| Metrics | Spring Boot Actuator exposes `/actuator/health` (DataSource + liveness) and `/actuator/info`; no custom metrics in initial delivery |
| Security | Parameterised queries via Spring Data JPA (no string-concatenated SQL); `SecureRandom` for code generation; URL stored as-is (no normalisation); no stack traces in HTTP error responses; HTTPS via reverse proxy in production |
| Rate Limiting | Not in scope for this version |
| Testing | ≥ 80% line coverage on `UrlShortenerService` and utility classes; unit tests with `MockitoExtension`; controller slice tests with `@WebMvcTest`; no `@SpringBootTest` full-context tests required for initial delivery |
| Health / Docs | `/actuator/health` returns `UP` within 30 s of container start; no API documentation endpoint (Swagger) required for initial delivery |

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `UrlShortenerController` | `UrlShortenerService` | Delegates all business logic to service layer |
| `UrlShortenerController` | `AppProperties` | Reads `app.base-url` for `shortUrl` construction; falls back to request `Host` header if blank |
| `UrlShortenerService` | `ShortUrlRepository` | Persists and looks up `ShortUrl` entities |
| `UrlShortenerService` | `AppProperties` | Reads `app.expiry-days` and `app.max-retries` |
| `GlobalExceptionHandler` | `UrlNotFoundException`, `UrlExpiredException`, `InvalidUrlException`, `CodeGenerationException` | Maps each to a specific HTTP status code |
| `GlobalExceptionHandler` | `ErrorResponse` | Serialised as JSON body for all error responses |
| `UrlShortenerService` | `UrlNotFoundException`, `UrlExpiredException`, `InvalidUrlException`, `CodeGenerationException` | Throws the appropriate exception for each error condition |
| `AppProperties` | `application.yml` keys `app.base-url`, `app.expiry-days`, `app.max-retries` | Binds via `@ConfigurationProperties(prefix = "app")` |
| Flyway | `source/src/main/resources/db/migration/V1__create_short_urls_table.sql` | Runs migration on startup; owns schema DDL |
| `ShortUrl` entity | `short_urls` table columns `id`, `code`, `original_url`, `created_at`, `expires_at` | Hibernate validates column mapping against Flyway-managed schema |

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Must come first; every other story requires the POM and package structure to exist.
2. **Story 2.1** — Flyway migration must be on the classpath before the app can start against PostgreSQL.
3. **Story 2.2** — JPA entity must exist before the repository can be typed against it.
4. **Story 2.3** — Repository depends on the entity.
5. **Story 3.1** — `AppProperties` must exist before the service and controller can inject it.
6. **Story 4.1** — Exception classes have no dependencies; can be written at any point but are needed by the service.
7. **Story 4.2** — Service depends on repository, `AppProperties`, and exception classes.
8. **Story 5.1** — DTOs have no dependencies; can be written in parallel with Story 4.2.
9. **Story 5.2** — Exception handler depends on exception classes and `ErrorResponse` DTO.
10. **Story 5.3** — Controller depends on service, DTOs, and `AppProperties`.
11. **Story 6.1** — Unit test for `generateCode()` depends on Story 4.2.
12. **Story 6.2** — Unit test for expiry logic depends on Story 4.2.
13. **Story 6.3** — Controller slice test depends on Stories 5.2 and 5.3.
14. **Story 7.1** — Dockerfile depends on the POM artifact name from Story 1.1.
15. **Story 7.2** — docker-compose depends on Dockerfile from Story 7.1.

> Stories 4.1 and 5.1 (exception classes and DTOs) can be developed in parallel with each other and in parallel with Story 3.1. Stories 6.1, 6.2, and 6.3 can be developed in parallel once their respective service/controller dependencies are complete. Stories 7.1 and 7.2 can be developed in parallel with the test epic.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required. The service is a JSON/redirect API consumed directly by HTTP clients; there is no browser UI, SPA, or server-rendered HTML in scope.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type | Fields | JSON field names | Notes |
|---|---|---|---|
| `ShortenRequest` | `url: String` | `url` | `@NotBlank` triggers HTTP 400; URL format validated in service → HTTP 422 |
| `ShortenResponse` | `code: String`, `shortUrl: String` | `code`, `shortUrl` | Returned on HTTP 201; `shortUrl` is fully qualified |
| `ErrorResponse` | `error: String` | `error` | Returned for all 4xx/5xx responses |
| `ShortUrl` (entity) | `id: Long`, `code: String`, `originalUrl: String`, `createdAt: OffsetDateTime`, `expiresAt: OffsetDateTime` | N/A (not serialised to HTTP) | Internal JPA entity only |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://localhost:5432/urlshortener` | PostgreSQL JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | `urlshortener` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | Yes | `secret` | PostgreSQL password |
| `APP_BASE_URL` | No | `http://localhost:8080` | Base URL prepended to codes in `shortUrl`; derived from `Host` header when blank |

### Database Schema

Table: `short_urls` — created by migration `V1__create_short_urls_table.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | `BIGSERIAL` | No | Primary key |
| `code` | `VARCHAR(6)` | No | Unique constraint `uq_short_urls_code` |
| `original_url` | `TEXT` | No | None |
| `created_at` | `TIMESTAMPTZ` | No | Default `NOW()` |
| `expires_at` | `TIMESTAMPTZ` | No | None |

Additional constraints:
- `CONSTRAINT uq_short_urls_code UNIQUE (code)` — enforces uniqueness at the database level as a final collision guard
- `CREATE INDEX idx_short_urls_code ON short_urls (code)` — explicit B-tree index for O(log n) code lookups

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Short code collision at scale | Low (62^6 ≈ 56.8B combinations, but UNIQUE constraint is global including expired rows) | Medium — `DataIntegrityViolationException` causes request failure without retry | Application retry loop up to `app.max-retries` (default 5) catches `DataIntegrityViolationException`; log WARN on each retry; log ERROR if all retries exhausted |
| Clock skew between app and PostgreSQL | Low | Medium — expiry checks using `OffsetDateTime.now()` in Java may differ from DB `NOW()` by seconds | Acceptable for 30-day TTL; for sub-second accuracy, use a `SELECT NOW()` query to get DB time, but this adds a round-trip — not required for initial delivery |
| Open redirect / SSRF abuse | Medium — any URL is accepted and redirected | High — phishing vectors; SSRF to internal services | Validate that scheme is `http` or `https` only (blocks `javascript:`, `file:`, `data:` URIs); service does not pre-fetch destinations; SSRF to private IP ranges is a follow-up enhancement |
| Unbounded table growth (no purge job) | High over time (all expired rows remain forever) | Low-Medium — index scans degrade as table grows | Add a scheduled job or database cron to `DELETE FROM short_urls WHERE expires_at < NOW() - interval '7 days'` as a follow-up task |
| Database connection exhaustion under spike | Medium | High — service unavailable | HikariCP `maximum-pool-size: 10`; `connection-timeout: 30000` ms causes a fast failure under pool pressure rather than indefinite queue |
| Missing input validation leading to XSS / injection | Medium | High | URL validated server-side with `URI.create` + scheme/host check; JSON error bodies (not HTML); parameterised JPA queries (no string concatenation) |
| Concurrent duplicate code generation | Low | Low — DB unique constraint is the final guard | `DataIntegrityViolationException` is caught and retried; unique constraint on `code` prevents two concurrent transactions from committing the same code |
| `original_url` storage size | Very Low — `TEXT` holds up to 1 GB per field | Very Low | No practical risk; `TEXT` is appropriate |

---

## DEFINITION OF DONE

### For Each Story
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing (target: ≥ 80% line coverage on service and utility classes)
- [ ] `@WebMvcTest` slice tests passing for all HTTP scenarios
- [ ] No new linting or compiler warnings (treat warnings as errors in CI)
- [ ] All acceptance criteria verified against a running local instance

### For the Release
- [ ] All stories complete and merged to `main`
- [ ] `./mvnw test` passes with ≥ 80% line coverage on `UrlShortenerService` (verified via JaCoCo report)
- [ ] `POST /shorten` and `GET /{code}` manually tested against a local PostgreSQL instance
- [ ] `docker compose up` starts successfully; `curl -s http://localhost:8080/actuator/health` returns `{"status":"UP"}`
- [ ] All environment variables documented in `README.md`
- [ ] No stack traces returned in any HTTP error response (verified by inspecting 400/404/410/422/500 responses)
- [ ] Docker image builds without errors: `docker build -t url-shortener ./source`

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven POM and main class; project cannot compile without it
2. **Story 2.1** — Flyway migration; schema must exist before entities can be validated
3. **Story 2.2** — `ShortUrl` entity; repository depends on it
4. **Story 2.3** — `ShortUrlRepository`; service depends on it
5. **Story 3.1** — `AppProperties` and `application.yml`; service and controller depend on it
6. **Story 4.1** — Exception classes; no dependencies, can be parallelised with Story 3.1
7. **Story 5.1** — DTOs; no dependencies, can be parallelised with Stories 3.1 and 4.1
8. **Story 4.2** — `UrlShortenerService`; depends on Stories 2.3, 3.1, 4.1
9. **Story 5.2** — `GlobalExceptionHandler`; depends on Stories 4.1 and 5.1
10. **Story 5.3** — `UrlShortenerController`; depends on Stories 4.2, 5.1, 5.2
11. **Story 6.1** — Code generator unit tests; depends on Story 4.2
12. **Story 6.2** — Expiry logic unit tests; depends on Story 4.2
13. **Story 6.3** — Controller slice tests; depends on Stories 5.2, 5.3
14. **Story 7.1** — Dockerfile; depends on Story 1.1 (artifact name)
15. **Story 7.2** — docker-compose; depends on Story 7.1

> **Parallel tracks available:** Stories 4.1, 5.1, and 3.1 can all be written simultaneously once Story 1.1 is done. Stories 6.1, 6.2, and 6.3 can be written in parallel once their upstream stories are ready. Stories 7.1 and 7.2 are fully independent of the test epic and can proceed in parallel.
