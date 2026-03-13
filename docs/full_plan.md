# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The MTI Scores API is a RESTful service that exposes Maritime Transportation Indicator (MTI) scores for vessels identified by their 7-digit IMO number. Consumers can retrieve the latest available scores, filter by year, or filter by a specific year-and-month combination. The API is built as a greenfield Spring Boot 3.x application backed by PostgreSQL, following a layered architecture (controller → service → repository) with cross-cutting concerns handled by servlet filters and a global exception handler. Security is enforced via strict input validation, parameterized SQL queries, and token-bucket rate limiting; all operations are traceable through a UUID request ID injected at the filter layer and propagated via SLF4J MDC.

---

## TECHNICAL ANALYSIS

### Recommended Stack (Greenfield)

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 17 | LTS release; records and sealed classes simplify DTOs; wide enterprise adoption |
| Build | Maven 3.9 | Standard dependency management; well-supported Spring Boot BOM |
| Framework | Spring Boot 3.2 | Auto-configuration, embedded Tomcat, built-in validation, production actuator |
| Database | PostgreSQL 15 | ACID compliance; NUMERIC type maps precisely to BigDecimal scores; strong index support |
| DB Migrations | Flyway 9 | Version-controlled schema; integrates natively with Spring Boot |
| JDBC | Spring JdbcTemplate | Lightweight; explicit parameterized queries prevent SQL injection without ORM overhead |
| Validation | Jakarta Bean Validation (Hibernate Validator) | Declarative constraint annotations on controller parameters |
| Rate Limiting | Bucket4j 8.x (in-memory) | Token-bucket algorithm; zero external dependencies for single-instance deployment |
| API Docs | SpringDoc OpenAPI 2.x | Auto-generates OpenAPI 3.0 from annotations; Swagger UI served at /swagger-ui.html |
| Logging | SLF4J + Logback | Standard Spring Boot logging; MDC support for request ID propagation |
| Testing | JUnit 5, Mockito, Testcontainers (PostgreSQL) | Unit + integration tests with real DB container |
| Containerization | Docker + docker-compose | Reproducible build and local dev environment |

### Project Structure

```
mti-scores-api/
└── source/
    ├── src/
    │   ├── main/
    │   │   ├── java/com/example/mti/
    │   │   │   ├── MtiScoresApplication.java
    │   │   │   ├── config/
    │   │   │   │   ├── RateLimitConfig.java
    │   │   │   │   └── OpenApiConfig.java
    │   │   │   ├── controller/
    │   │   │   │   └── MtiScoresController.java
    │   │   │   ├── service/
    │   │   │   │   └── MtiScoresService.java
    │   │   │   ├── repository/
    │   │   │   │   └── MtiScoresRepository.java
    │   │   │   ├── model/
    │   │   │   │   └── MtiScore.java
    │   │   │   ├── dto/
    │   │   │   │   ├── MetaDto.java
    │   │   │   │   ├── ScoresDto.java
    │   │   │   │   ├── MetadataDto.java
    │   │   │   │   ├── MtiScoreDataDto.java
    │   │   │   │   ├── ApiResponse.java
    │   │   │   │   └── ErrorDataDto.java
    │   │   │   ├── filter/
    │   │   │   │   ├── RequestIdFilter.java
    │   │   │   │   └── RateLimitFilter.java
    │   │   │   ├── exception/
    │   │   │   │   ├── MtiException.java
    │   │   │   │   └── GlobalExceptionHandler.java
    │   │   │   └── constant/
    │   │   │       └── ErrorCode.java
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       └── db/migration/
    │   │           ├── V1__create_mti_scores_history.sql
    │   │           └── V2__seed_test_data.sql
    │   └── test/
    │       ├── java/com/example/mti/
    │       │   ├── service/
    │       │   │   └── MtiScoresServiceTest.java
    │       │   ├── controller/
    │       │   │   └── MtiScoresControllerTest.java
    │       │   ├── repository/
    │       │   │   └── MtiScoresRepositoryTest.java
    │       │   └── integration/
    │       │       └── MtiScoresIntegrationTest.java
    │       └── resources/
    │           └── application-test.yml
    ├── Dockerfile
    ├── docker-compose.yml
    └── pom.xml
```

### Integration Points

- **PostgreSQL database** — single table `mti_scores_history`; accessed exclusively via `JdbcTemplate` with parameterized queries
- **No external API dependencies** — all data is read from the local database
- **Actuator health endpoint** at `/actuator/health` for liveness/readiness probes
- **Swagger UI** at `/swagger-ui.html`; OpenAPI JSON at `/v3/api-docs`

### Technical Constraints

- IMO number must match regex `^[0-9]{7}$`; validated before any DB call
- Year must be between 2000 and 2100 (inclusive); month must be between 1 and 12 (inclusive)
- Month parameter is only valid when year is also provided; violating this returns ERR_102
- NULL score fields in the database must serialize as `null` in JSON (not 0 or omitted)
- Rate limit: 100 requests per minute per remote IP address
- All SQL queries must use parameterized placeholders (no string concatenation)
- Index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is required for query performance
- p95 latency target: under 100 ms for cached/indexed queries

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.mti` | **Group ID:** `com.example` | **Artifact ID:** `mti-scores-api`

### Overview

The backend implements a single GET endpoint `GET /api/v1/vessels/{imo}/mti-scores` with optional `year` and `month` query parameters. It validates inputs, routes to one of three SQL queries depending on which parameters are present, maps rows to a domain model, and returns a structured JSON response. All errors are handled centrally by a `@RestControllerAdvice` handler. A servlet filter injects a UUID request ID into every request for traceability.

---

### Epic 1: Project Scaffolding & Configuration

**Goal:** Create a runnable Spring Boot project with correct dependencies, configuration, and Docker support
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Maven project setup

**As a** developer **I want** a properly configured Maven project **so that** all dependencies are resolved and the application compiles and starts

**Acceptance Criteria:**
- [ ] `pom.xml` declares Spring Boot 3.2.x parent BOM
- [ ] All required dependencies are declared with correct artifact IDs and scopes
- [ ] `mvn spring-boot:run` starts the application without errors
- [ ] `mvn test` runs without compilation errors

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `source/pom.xml`

Create the Maven project descriptor with group ID `com.example`, artifact ID `mti-scores-api`, version `1.0.0-SNAPSHOT`, Java 17 source/target, and Spring Boot parent `3.2.x`. Declare the following dependencies: `org.springframework.boot:spring-boot-starter-web` (embedded Tomcat, MVC), `org.springframework.boot:spring-boot-starter-jdbc` (JdbcTemplate), `org.springframework.boot:spring-boot-starter-validation` (Jakarta Bean Validation), `org.springframework.boot:spring-boot-starter-actuator` (health endpoint), `org.flywaydb:flyway-core` (schema migrations), `org.postgresql:postgresql` (JDBC driver, runtime scope), `com.github.ben-manes.caffeine:caffeine:3.1.8` (in-memory cache backing for Bucket4j), `com.bucket4j:bucket4j-core:8.7.0` (rate limiting), `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0` (Swagger UI + OpenAPI 3). For tests declare: `org.springframework.boot:spring-boot-starter-test` (test scope), `org.testcontainers:junit-jupiter:1.19.x` (test scope), `org.testcontainers:postgresql:1.19.x` (test scope). Add the `spring-boot-maven-plugin` in the build section for fat-JAR packaging.

**Task 1.1.b — Create application entry point** — file: `source/src/main/java/com/example/mti/MtiScoresApplication.java`

Create class `MtiScoresApplication` annotated with `@SpringBootApplication`. Implement the standard `public static void main(String[] args)` method that calls `SpringApplication.run(MtiScoresApplication.class, args)`. No additional configuration is needed in this class.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Application configuration

**As a** developer **I want** a well-structured `application.yml` **so that** all configurable values (datasource, server port, logging) are centralized and environment-variable-driven

**Background for implementer:** Spring Boot resolves `${ENV_VAR:default}` syntax in YAML. All database credentials are externalized to environment variables so that the same artifact can run in different environments without rebuilding. Flyway is configured to run automatically on startup from `classpath:db/migration`.

**Acceptance Criteria:**
- [ ] `application.yml` reads `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` from environment
- [ ] Server listens on port 8080
- [ ] Flyway migrations run on startup from `classpath:db/migration`
- [ ] Actuator health endpoint is exposed
- [ ] Log level for `com.example.mti` package is INFO

**Tasks:**

**Task 1.2.a — Create application.yml** — file: `source/src/main/resources/application.yml`

Create the main configuration file with the following sections. Under `server.port`: `8080`. Under `spring.datasource`: `url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/mtidb}`, `username: ${DATABASE_USERNAME:mti}`, `password: ${DATABASE_PASSWORD:mti}`, `driver-class-name: org.postgresql.Driver`. Under `spring.flyway`: `enabled: true`, `locations: classpath:db/migration`. Under `management.endpoints.web.exposure.include`: `health`. Under `logging.level.com.example.mti`: `INFO`. Under `springdoc.swagger-ui.path`: `/swagger-ui.html`.

**Task 1.2.b — Create test application configuration** — file: `source/src/test/resources/application-test.yml`

Create the test profile configuration. Leave datasource URL blank (Testcontainers will override `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` programmatically via `@DynamicPropertySource`). Set `spring.flyway.enabled: true` so migrations run against the Testcontainers PostgreSQL instance. Set `logging.level.com.example.mti: DEBUG` for verbose test output.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 1.3: Docker setup

**As a** developer **I want** a Dockerfile and docker-compose file **so that** the application and its PostgreSQL dependency can be run locally with a single command

**Acceptance Criteria:**
- [ ] `Dockerfile` builds a runnable image using multi-stage build
- [ ] `docker-compose.yml` starts both the app and PostgreSQL
- [ ] `docker compose up` results in the API responding on port 8080

**Tasks:**

**Task 1.3.a — Create Dockerfile** — file: `source/Dockerfile`

Create a two-stage Dockerfile. Stage 1 (`builder`) uses image `maven:3.9-eclipse-temurin-17`; copies `pom.xml` and `src/` into `/build`; runs `mvn -B package -DskipTests` to produce `target/mti-scores-api-1.0.0-SNAPSHOT.jar`. Stage 2 uses image `eclipse-temurin:17-jre-jammy`; copies the JAR from stage 1 as `/app/app.jar`; declares `EXPOSE 8080`; sets `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`. No `CMD` override is needed.

**Task 1.3.b — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create a Compose file with two services. Service `db` uses image `postgres:15-alpine`, sets environment `POSTGRES_DB=mtidb`, `POSTGRES_USER=mti`, `POSTGRES_PASSWORD=mti`, exposes port `5432:5432`, and declares a named volume `pgdata` mounted at `/var/lib/postgresql/data`. Service `app` is built from the local Dockerfile (`build: .`), depends on `db`, sets environment variables `DATABASE_URL=jdbc:postgresql://db:5432/mtidb`, `DATABASE_USERNAME=mti`, `DATABASE_PASSWORD=mti`, and exposes port `8080:8080`. Declare the top-level `volumes` key with `pgdata`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Database Layer

**Goal:** Define the schema, domain model, and repository with all three query variants
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Flyway schema migration

**As a** developer **I want** an automatically applied database migration **so that** the `mti_scores_history` table exists with the correct schema and indexes when the application starts

**Background for implementer:** Flyway applies versioned migration scripts in order at startup. The script must be placed in `src/main/resources/db/migration/` and named `V1__create_mti_scores_history.sql`. Score columns are `NUMERIC(5,2)` to match the two-decimal precision shown in the API examples. All score columns are nullable because the PRD explicitly requires returning `null` for missing score fields (AC8). The composite index `(imo_number, year DESC, month DESC)` is required by the PRD's implementation notes for query performance.

**Acceptance Criteria:**
- [ ] Migration runs without errors on a fresh PostgreSQL 15 database
- [ ] Table `mti_scores_history` has all required columns with correct types
- [ ] Composite index `idx_mti_scores_imo_year_month` exists
- [ ] `created_at` and `updated_at` default to `NOW()`

**Tasks:**

**Task 2.1.a — Create V1 schema migration** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Write a migration that creates table `mti_scores_history` with columns: `id BIGSERIAL PRIMARY KEY`, `imo_number VARCHAR(7) NOT NULL`, `year INTEGER NOT NULL`, `month INTEGER NOT NULL`, `mti_score NUMERIC(5,2)`, `vessel_score NUMERIC(5,2)`, `reporting_score NUMERIC(5,2)`, `voyages_score NUMERIC(5,2)`, `emissions_score NUMERIC(5,2)`, `sanctions_score NUMERIC(5,2)`, `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`. After the table definition, add `CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC)`. Add a unique constraint: `ALTER TABLE mti_scores_history ADD CONSTRAINT uq_imo_year_month UNIQUE (imo_number, year, month)` to prevent duplicate entries.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 2.2: MtiScore domain model

**As a** developer **I want** a plain Java class representing a row from `mti_scores_history` **so that** the repository can map result sets and the service can operate on typed data

**Acceptance Criteria:**
- [ ] `MtiScore` has fields for all table columns
- [ ] Score fields are typed as `BigDecimal` and nullable
- [ ] Timestamp fields use `OffsetDateTime`
- [ ] Class has a no-args constructor and all-args constructor (or uses a Java record)

**Tasks:**

**Task 2.2.a — Create MtiScore model** — file: `source/src/main/java/com/example/mti/model/MtiScore.java`

Create class `MtiScore` (a plain Java class or record — prefer a record for immutability). It must have the following fields exactly: `Long id`, `String imoNumber`, `Integer year`, `Integer month`, `BigDecimal mtiScore`, `BigDecimal vesselScore`, `BigDecimal reportingScore`, `BigDecimal voyagesScore`, `BigDecimal emissionsScore`, `BigDecimal sanctionsScore`, `OffsetDateTime createdAt`, `OffsetDateTime updatedAt`. If using a class rather than a record, provide a public all-args constructor and public getters for all fields. No JPA annotations are used — this is a plain POJO.

**Complexity:** S | **Dependencies:** Story 2.1

---

#### Story 2.3: MtiScoresRepository

**As a** developer **I want** a repository class that encapsulates all database queries **so that** the service layer can retrieve MTI scores without writing SQL directly

**Background for implementer:** `JdbcTemplate` is used instead of JPA/Hibernate to keep query logic explicit and auditable — the PRD specifies exact SQL for each filter variant. All three queries use `queryForObject` or `query` with parameterized `?` placeholders (never string concatenation) to prevent SQL injection. When `queryForObject` finds no rows it throws `EmptyResultDataAccessException`; the repository catches this and returns `Optional.empty()` so the service can map it to ERR_101 without a stack trace. The `RowMapper` lambda maps each column by name using `rs.getString("imo_number")`, `rs.getInt("year")`, etc.

**Acceptance Criteria:**
- [ ] `MtiScoresRepository` is annotated `@Repository`
- [ ] Method `findLatest(String imoNumber)` returns `Optional<MtiScore>`
- [ ] Method `findLatestByYear(String imoNumber, int year)` returns `Optional<MtiScore>`
- [ ] Method `findByYearAndMonth(String imoNumber, int year, int month)` returns `Optional<MtiScore>`
- [ ] All queries use parameterized placeholders
- [ ] `EmptyResultDataAccessException` is caught and converted to `Optional.empty()`

**Tasks:**

**Task 2.3.a — Create MtiScoresRepository** — file: `source/src/main/java/com/example/mti/repository/MtiScoresRepository.java`

Create class `MtiScoresRepository` annotated with `@Repository`. Inject `JdbcTemplate jdbcTemplate` via constructor injection. Define a private `RowMapper<MtiScore> ROW_MAPPER` field (static final) that maps columns: `rs.getLong("id")` → `id`; `rs.getString("imo_number")` → `imoNumber`; `rs.getInt("year")` → `year`; `rs.getInt("month")` → `month`; `rs.getBigDecimal("mti_score")` → `mtiScore`; `rs.getBigDecimal("vessel_score")` → `vesselScore`; `rs.getBigDecimal("reporting_score")` → `reportingScore`; `rs.getBigDecimal("voyages_score")` → `voyagesScore`; `rs.getBigDecimal("emissions_score")` → `emissionsScore`; `rs.getBigDecimal("sanctions_score")` → `sanctionsScore`; `rs.getObject("created_at", OffsetDateTime.class)` → `createdAt`; `rs.getObject("updated_at", OffsetDateTime.class)` → `updatedAt`. Implement method `findLatest(String imoNumber)` returning `Optional<MtiScore>`: execute SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1` with parameter `imoNumber`; catch `EmptyResultDataAccessException` and return `Optional.empty()`; otherwise return `Optional.of(result)`. Implement `findLatestByYear(String imoNumber, int year)` returning `Optional<MtiScore>`: execute SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1` with parameters `imoNumber`, `year`; same exception handling. Implement `findByYearAndMonth(String imoNumber, int year, int month)` returning `Optional<MtiScore>`: execute SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1` with parameters `imoNumber`, `year`, `month`; same exception handling. Add `private static final Logger log = LoggerFactory.getLogger(MtiScoresRepository.class)`. Log at DEBUG before each query: `log.debug("findLatest imoNumber={}", imoNumber)`, `log.debug("findLatestByYear imoNumber={} year={}", imoNumber, year)`, `log.debug("findByYearAndMonth imoNumber={} year={} month={}", imoNumber, year, month)`.

**Complexity:** M | **Dependencies:** Story 2.2

---

### Epic 3: Cross-Cutting Infrastructure

**Goal:** Implement request ID propagation, structured error codes, and centralized exception handling
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: RequestIdFilter

**As a** developer **I want** a servlet filter that generates and attaches a UUID request ID to every request **so that** all log lines and API responses share a traceable correlation ID

**Background for implementer:** The filter runs before any controller or rate-limit logic (order 1). It generates a `UUID.randomUUID()` string, stores it as a request attribute under key `RequestIdFilter.REQUEST_ID_ATTR`, puts it into SLF4J's `MDC` under key `RequestIdFilter.MDC_REQUEST_ID_KEY`, and adds it to the response header `X-Request-ID`. The MDC must be cleared in a `finally` block to prevent leaking the request ID to the next request on the same thread (thread pool reuse). The controller reads the attribute to populate the `meta.request_id` field in responses.

**Acceptance Criteria:**
- [ ] Filter registered with `@Component` and `@Order(1)`
- [ ] Every response includes `X-Request-ID` header
- [ ] `REQUEST_ID_ATTR` constant equals `"requestId"`
- [ ] `MDC_REQUEST_ID_KEY` constant equals `"requestId"`
- [ ] MDC is cleared after the response is committed

**Tasks:**

**Task 3.1.a — Create RequestIdFilter** — file: `source/src/main/java/com/example/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` extending `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"` and `public static final String MDC_REQUEST_ID_KEY = "requestId"`. Declare `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`. Implement `doFilter(ServletRequest request, ServletResponse response, FilterChain chain)`: cast `request` to `HttpServletRequest` and `response` to `HttpServletResponse`; generate `String requestId = UUID.randomUUID().toString()`; call `request.setAttribute(REQUEST_ID_ATTR, requestId)`; call `MDC.put(MDC_REQUEST_ID_KEY, requestId)`; call `response.setHeader("X-Request-ID", requestId)`; wrap `chain.doFilter(request, response)` in a try-finally block where the finally block calls `MDC.remove(MDC_REQUEST_ID_KEY)`. Log at DEBUG after setting the ID: `log.debug("Assigned requestId={}", requestId)`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 3.2: ErrorCode enum and MtiException

**As a** developer **I want** a typed `ErrorCode` enum and `MtiException` class **so that** any layer can signal a business error with its associated HTTP status without hard-coding strings

**Background for implementer:** Centralizing error metadata in an enum ensures the error code string (e.g., "ERR_101"), title, and HTTP status are always consistent. `MtiException` is an unchecked exception that wraps an `ErrorCode` and an optional detail message. The global exception handler catches `MtiException` and reads the code, title, and status from the enum.

**Acceptance Criteria:**
- [ ] `ErrorCode` enum has entries `ERR_101` through `ERR_105` with correct HTTP statuses and titles
- [ ] `MtiException` is unchecked, has a constructor accepting `ErrorCode` and `String message`
- [ ] `MtiException.getErrorCode()` returns the `ErrorCode`

**Tasks:**

**Task 3.2.a — Create ErrorCode enum** — file: `source/src/main/java/com/example/mti/constant/ErrorCode.java`

Create enum `ErrorCode` with the following entries, each carrying a `String code`, `String title`, and `int httpStatus` as constructor parameters: `ERR_101("ERR_101", "Resource Not Found", 404)`, `ERR_102("ERR_102", "Invalid Parameters", 400)`, `ERR_103("ERR_103", "Invalid IMO Format", 400)`, `ERR_104("ERR_104", "Invalid Date Range", 400)`, `ERR_105("ERR_105", "Internal Server Error", 500)`. Provide private final fields `code`, `title`, `httpStatus` and a constructor that sets them. Provide public getters `getCode()`, `getTitle()`, `getHttpStatus()`.

**Task 3.2.b — Create MtiException** — file: `source/src/main/java/com/example/mti/exception/MtiException.java`

Create class `MtiException` extending `RuntimeException`. Declare private final field `ErrorCode errorCode`. Provide constructor `MtiException(ErrorCode errorCode, String message)` that calls `super(message)` and sets `this.errorCode = errorCode`. Provide a second constructor `MtiException(ErrorCode errorCode)` that delegates to `this(errorCode, errorCode.getTitle())`. Provide public getter `getErrorCode()` returning `ErrorCode`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 3.3: GlobalExceptionHandler

**As a** developer **I want** a centralized exception handler **so that** all errors produce the standard `{meta, data}` error response format regardless of where the exception was thrown

**Background for implementer:** `@RestControllerAdvice` intercepts exceptions after the controller layer, so the request attribute `RequestIdFilter.REQUEST_ID_ATTR` is still accessible via `HttpServletRequest`. `ConstraintViolationException` is thrown by Bean Validation when path/query parameters fail `@Pattern`, `@Min`, or `@Max` constraints — these are mapped to ERR_103 or ERR_104 depending on the failing field. A catch-all `Exception` handler maps to ERR_105 and logs at ERROR to avoid swallowing unexpected failures.

**Acceptance Criteria:**
- [ ] `MtiException` is handled; response uses the exception's `ErrorCode` for code, title, and HTTP status
- [ ] `ConstraintViolationException` from IMO pattern validation produces ERR_103 (400)
- [ ] `ConstraintViolationException` from year/month range validation produces ERR_104 (400)
- [ ] Unexpected `Exception` produces ERR_105 (500) and logs at ERROR
- [ ] All error responses include `meta.request_id` read from the request attribute
- [ ] All error responses include `meta.request_timestamp` as the current UTC ISO-8601 instant

**Tasks:**

**Task 3.3.a — Create GlobalExceptionHandler** — file: `source/src/main/java/com/example/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` annotated with `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`. Implement private helper method `buildErrorResponse(HttpServletRequest request, ErrorCode errorCode, String message)` returning `ResponseEntity<ApiResponse<ErrorDataDto>>`: read `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; construct `MetaDto` with `requestId` and `OffsetDateTime.now(ZoneOffset.UTC).toString()`; construct `ErrorDataDto` with `errorCode.getCode()`, `errorCode.getTitle()`, and `message`; wrap in `ApiResponse<ErrorDataDto>`; return `ResponseEntity.status(errorCode.getHttpStatus()).body(...)`. Implement `@ExceptionHandler(MtiException.class)` method `handleMtiException(MtiException ex, HttpServletRequest request)`: log at WARN `log.warn("Business error errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage())`; return `buildErrorResponse(request, ex.getErrorCode(), ex.getMessage())`. Implement `@ExceptionHandler(ConstraintViolationException.class)` method `handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request)`: inspect the first violation's property path string; if it contains `"imo"` return `buildErrorResponse(request, ErrorCode.ERR_103, "IMO number must be exactly 7 digits")`; otherwise return `buildErrorResponse(request, ErrorCode.ERR_104, "Invalid year or month value")`; log at WARN `log.warn("Constraint violation path={} message={}", firstViolation.getPropertyPath(), firstViolation.getMessage())`. Implement `@ExceptionHandler(Exception.class)` method `handleUnexpected(Exception ex, HttpServletRequest request)`: log at ERROR `log.error("Unexpected error requestId={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), ex)`; return `buildErrorResponse(request, ErrorCode.ERR_105, "An internal error occurred")`.

**Complexity:** M | **Dependencies:** Stories 3.1, 3.2

---

### Epic 4: DTOs, Business Logic, and REST API

**Goal:** Implement the response DTOs, the core business logic service, and the REST controller
**Priority:** High | **Estimated Complexity:** L

---

#### Story 4.1: Response DTOs

**As a** developer **I want** typed DTO classes for both success and error responses **so that** Jackson serializes the correct JSON structure with the right field names

**Background for implementer:** All DTOs use `@JsonInclude(JsonInclude.Include.ALWAYS)` at the class level to ensure nullable score fields serialize as `null` (not omitted), satisfying AC8. The wrapper `ApiResponse<T>` holds a `MetaDto meta` and a `T data` — this generic wrapper serves both success and error responses. Java records are preferred for brevity, but all fields must be public and Jackson-accessible.

**Acceptance Criteria:**
- [ ] `ApiResponse<T>` has fields `meta` (MetaDto) and `data` (T); serializes as `{"meta":{...},"data":{...}}`
- [ ] `MetaDto` has `request_id` (String) and `request_timestamp` (String) serialized with underscores
- [ ] `ScoresDto` has all six score fields typed `BigDecimal`; null values serialize as `null`
- [ ] `MtiScoreDataDto` has `imo_number`, `year`, `month`, `scores` (ScoresDto), `metadata` (MetadataDto)
- [ ] `ErrorDataDto` has `error_code`, `title`, `message`

**Tasks:**

**Task 4.1.a — Create ApiResponse generic wrapper** — file: `source/src/main/java/com/example/mti/dto/ApiResponse.java`

Create class `ApiResponse<T>` (a Java record or class). Fields: `MetaDto meta` and `T data`. Annotate with `@JsonInclude(JsonInclude.Include.ALWAYS)`. If a class, provide an all-args constructor and public getters. No additional annotations needed — Jackson will serialize fields using their exact Java names. Ensure the class is public.

**Task 4.1.b — Create MetaDto** — file: `source/src/main/java/com/example/mti/dto/MetaDto.java`

Create class `MetaDto` (record or class). Fields: `String requestId` and `String requestTimestamp`. Annotate `requestId` with `@JsonProperty("request_id")` and `requestTimestamp` with `@JsonProperty("request_timestamp")` to match the snake_case JSON contract. Provide all-args constructor.

**Task 4.1.c — Create ScoresDto** — file: `source/src/main/java/com/example/mti/dto/ScoresDto.java`

Create class `ScoresDto` (record or class). Fields (all typed `BigDecimal`, all nullable): `BigDecimal mtiScore`, `BigDecimal vesselScore`, `BigDecimal reportingScore`, `BigDecimal voyagesScore`, `BigDecimal emissionsScore`, `BigDecimal sanctionsScore`. Annotate each field with `@JsonProperty` using the snake_case name: `"mti_score"`, `"vessel_score"`, `"reporting_score"`, `"voyages_score"`, `"emissions_score"`, `"sanctions_score"`. Annotate the class with `@JsonInclude(JsonInclude.Include.ALWAYS)` so null scores serialize as `null`.

**Task 4.1.d — Create MetadataDto** — file: `source/src/main/java/com/example/mti/dto/MetadataDto.java`

Create class `MetadataDto` (record or class). Fields: `String createdAt` and `String updatedAt`. Annotate with `@JsonProperty("created_at")` and `@JsonProperty("updated_at")`. Timestamps will be pre-formatted as ISO-8601 strings by the service layer before being set here.

**Task 4.1.e — Create MtiScoreDataDto** — file: `source/src/main/java/com/example/mti/dto/MtiScoreDataDto.java`

Create class `MtiScoreDataDto` (record or class). Fields: `String imoNumber` annotated `@JsonProperty("imo_number")`, `Integer year`, `Integer month`, `ScoresDto scores`, `MetadataDto metadata`. Provide all-args constructor. This DTO maps directly to the `data` object in a success response.

**Task 4.1.f — Create ErrorDataDto** — file: `source/src/main/java/com/example/mti/dto/ErrorDataDto.java`

Create class `ErrorDataDto` (record or class). Fields: `String errorCode` annotated `@JsonProperty("error_code")`, `String title`, `String message`. Provide all-args constructor. This DTO maps directly to the `data` object in an error response.

**Complexity:** S | **Dependencies:** None

---

#### Story 4.2: MtiScoresService

**As a** developer **I want** a service class that applies business rules and routes to the correct repository query **so that** the controller stays thin and query selection logic is testable in isolation

**Background for implementer:** The service is the only place that applies the "month without year" validation rule (ERR_102) and the "no data found" check (ERR_101). It intentionally does not validate IMO format or numeric ranges — that is done declaratively by Bean Validation annotations on the controller. The service also maps the `MtiScore` domain model to the response DTOs, converting `OffsetDateTime` fields to ISO-8601 strings using `DateTimeFormatter.ISO_OFFSET_DATE_TIME`.

**Acceptance Criteria:**
- [ ] `MtiScoresService` is annotated `@Service`
- [ ] `getScores(String imoNumber, Integer year, Integer month)` returns `MtiScoreDataDto`
- [ ] When `month != null && year == null`, throws `MtiException(ErrorCode.ERR_102)`
- [ ] When no row is found, throws `MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`
- [ ] Calls `findLatest` when year is null; `findLatestByYear` when only year is given; `findByYearAndMonth` when both are given
- [ ] Maps `MtiScore` to `MtiScoreDataDto` correctly

**Tasks:**

**Task 4.2.a — Create MtiScoresService** — file: `source/src/main/java/com/example/mti/service/MtiScoresService.java`

Create class `MtiScoresService` annotated with `@Service`. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class)`. Inject `MtiScoresRepository repository` via constructor. Implement public method `getScores(String imoNumber, Integer year, Integer month)` returning `MtiScoreDataDto`: log at INFO `log.info("getScores start imoNumber={} year={} month={}", imoNumber, year, month)`. Check: if `month != null && year == null`, log at WARN `log.warn("Month without year rejected imoNumber={} month={}", imoNumber, month)` and throw `new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`. Route to the appropriate repository method: if `year == null`, call `repository.findLatest(imoNumber)`; else if `month == null`, call `repository.findLatestByYear(imoNumber, year)`; else call `repository.findByYearAndMonth(imoNumber, year, month)`. Assign the result to `Optional<MtiScore> result`. If `result.isEmpty()`, log at WARN `log.warn("No scores found imoNumber={} year={} month={}", imoNumber, year, month)` and throw `new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`. Otherwise map the `MtiScore` to `MtiScoreDataDto`: construct `ScoresDto` from all six score fields (passing null directly for null BigDecimal values); construct `MetadataDto` using `score.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)` and `score.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)`; construct `MtiScoreDataDto` with `score.getImoNumber()`, `score.getYear()`, `score.getMonth()`, `scoresDto`, `metadataDto`; log at INFO `log.info("getScores success imoNumber={} year={} month={}", imoNumber, score.getYear(), score.getMonth())`; return the DTO. Implement private helper method `toScoresDto(MtiScore score)` returning `ScoresDto` to keep the mapping logic readable.

**Complexity:** M | **Dependencies:** Stories 2.3, 3.2, 4.1

---

#### Story 4.3: MtiScoresController

**As a** developer **I want** a REST controller that exposes `GET /api/v1/vessels/{imo}/mti-scores` **so that** clients can retrieve MTI scores via HTTP

**Background for implementer:** Bean Validation annotations on method parameters require the class to be annotated `@Validated` (not `@Valid`). `@Pattern(regexp = "^[0-9]{7}$")` on the `imo` path variable triggers `ConstraintViolationException` before the service is called if the format is wrong, which the `GlobalExceptionHandler` maps to ERR_103. `@Min` and `@Max` on `year` and `month` similarly trigger ERR_104. The controller reads the request ID from `HttpServletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` to populate the `meta` field; it also records the current timestamp as `OffsetDateTime.now(ZoneOffset.UTC)` formatted to ISO-8601.

**Acceptance Criteria:**
- [ ] Controller annotated `@RestController`, `@RequestMapping("/api/v1")`, `@Validated`
- [ ] GET endpoint at `/vessels/{imo}/mti-scores` returns 200 with `ApiResponse<MtiScoreDataDto>`
- [ ] `imo` validated with `@Pattern(regexp = "^[0-9]{7}$")`
- [ ] `year` validated with `@Min(2000) @Max(2100)` when present
- [ ] `month` validated with `@Min(1) @Max(12)` when present
- [ ] `meta.request_id` is taken from `RequestIdFilter.REQUEST_ID_ATTR` request attribute
- [ ] `meta.request_timestamp` is the current UTC time as ISO-8601

**Tasks:**

**Task 4.3.a — Create MtiScoresController** — file: `source/src/main/java/com/example/mti/controller/MtiScoresController.java`

Create class `MtiScoresController` annotated with `@RestController`, `@RequestMapping("/api/v1")`, and `@Validated`. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresController.class)`. Inject `MtiScoresService service` via constructor. Implement method `getMtiScores` annotated with `@GetMapping("/vessels/{imo}/mti-scores")`, returning `ResponseEntity<ApiResponse<MtiScoreDataDto>>`. Method parameters: `@PathVariable @Pattern(regexp = "^[0-9]{7}$", message = "IMO number must be exactly 7 digits") String imo`; `@RequestParam(required = false) @Min(value = 2000, message = "Year must be >= 2000") @Max(value = 2100, message = "Year must be <= 2100") Integer year`; `@RequestParam(required = false) @Min(value = 1, message = "Month must be >= 1") @Max(value = 12, message = "Month must be <= 12") Integer month`; `HttpServletRequest request`. In the method body: log at INFO `log.info("Request getMtiScores imo={} year={} month={}", imo, year, month)`. Call `MtiScoreDataDto data = service.getScores(imo, year, month)`. Construct `MetaDto meta` with `requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` and `requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)`. Construct `ApiResponse<MtiScoreDataDto> response = new ApiResponse<>(meta, data)`. Log at INFO `log.info("Response getMtiScores imo={} status=200", imo)`. Return `ResponseEntity.ok(response)`.

**Complexity:** M | **Dependencies:** Stories 3.1, 3.3, 4.1, 4.2

---

### Epic 5: Rate Limiting

**Goal:** Enforce 100 requests per minute per client IP using a token-bucket algorithm
**Priority:** Medium | **Estimated Complexity:** M

---

#### Story 5.1: RateLimitFilter and configuration

**As a** developer **I want** a rate-limiting filter **so that** no single IP can make more than 100 requests per minute, matching the PRD security requirement

**Background for implementer:** Bucket4j's `Bandwidth.classic` creates a token-bucket refilled at a fixed rate. Each client IP gets its own `Bucket` instance stored in a `ConcurrentHashMap<String, Bucket>`. The map is created as a Spring bean (`@Bean`) in `RateLimitConfig` and injected into the filter. On each request: get or create the bucket for the client's `request.getRemoteAddr()`; call `bucket.tryConsume(1)`; if false, set HTTP status 429 and write a JSON error body directly (no exception is thrown because the response is committed before the controller runs). The filter runs at `@Order(2)` — after `RequestIdFilter` so the request ID is already available.

**Acceptance Criteria:**
- [ ] Filter runs at `@Order(2)`
- [ ] Each unique IP gets its own `Bucket` with capacity 100, refill rate 100 per 60 seconds
- [ ] When rate limit is exceeded, response is HTTP 429 with body `{"meta":{...},"data":{"error_code":"ERR_106","title":"Too Many Requests","message":"Rate limit exceeded, try again later"}}`
- [ ] Requests within the limit proceed normally

**Tasks:**

**Task 5.1.a — Create RateLimitConfig** — file: `source/src/main/java/com/example/mti/config/RateLimitConfig.java`

Create class `RateLimitConfig` annotated with `@Configuration`. Declare a `@Bean` method `rateLimitBuckets()` returning `ConcurrentHashMap<String, Bucket>` that returns `new ConcurrentHashMap<>()`. This bean holds per-IP bucket instances and is injected into `RateLimitFilter`. No additional configuration is needed; the bucket parameters are defined in the filter itself.

**Task 5.1.b — Create RateLimitFilter** — file: `source/src/main/java/com/example/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` implementing `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(2)`. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`. Inject `ConcurrentHashMap<String, Bucket> rateLimitBuckets` via constructor. Implement `doFilter(ServletRequest request, ServletResponse response, FilterChain chain)`: cast to `HttpServletRequest` and `HttpServletResponse`. Get `String clientIp = httpRequest.getRemoteAddr()`. Use `rateLimitBuckets.computeIfAbsent(clientIp, ip -> createBucket())` to get the client's bucket. Call `bucket.tryConsume(1)`: if true, call `chain.doFilter(request, response)` and return. If false, log at WARN `log.warn("Rate limit exceeded clientIp={} requestId={}", clientIp, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR))`. Set `httpResponse.setStatus(429)`, `httpResponse.setContentType("application/json")`. Write the following literal JSON to `httpResponse.getWriter()`: `{"meta":{"request_id":"<requestId>","request_timestamp":"<now>"},"data":{"error_code":"ERR_106","title":"Too Many Requests","message":"Rate limit exceeded, try again later"}}` where `<requestId>` is read from `request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` and `<now>` is `OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)`. Implement private method `createBucket()` returning `Bucket`: use `Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)))` to create a `Bandwidth`; return `Bucket.builder().addLimit(bandwidth).build()`.

**Complexity:** M | **Dependencies:** Stories 3.1, 1.1

---

### Epic 6: Testing

**Goal:** Achieve ≥80% code coverage with unit and integration tests covering all acceptance criteria
**Priority:** High | **Estimated Complexity:** L

---

#### Story 6.1: Seed data migration for tests

**As a** developer **I want** a V2 Flyway migration with known test data **so that** integration tests can make assertions against predictable rows

**Background for implementer:** The seed data migration is applied to both the test Testcontainers instance (via the standard Flyway auto-run) and the local dev database (via docker-compose). Exact values are required so test assertions can reference specific numbers. A row with null score fields is included to cover AC8.

**Acceptance Criteria:**
- [ ] Seed migration `V2__seed_test_data.sql` inserts four specific rows

**Tasks:**

**Task 6.1.a — Create V2 seed data migration** — file: `source/src/main/resources/db/migration/V2__seed_test_data.sql`

Write a migration that inserts exactly the following rows into `mti_scores_history`. Row 1: `imo_number='9123456'`, `year=2024`, `month=1`, `mti_score=85.50`, `vessel_score=90.00`, `reporting_score=88.75`, `voyages_score=82.30`, `emissions_score=87.60`, `sanctions_score=100.00`, `created_at='2024-01-01T00:00:00Z'`, `updated_at='2024-01-01T00:00:00Z'`. Row 2: `imo_number='9123456'`, `year=2023`, `month=12`, `mti_score=80.00`, `vessel_score=85.00`, `reporting_score=82.50`, `voyages_score=79.00`, `emissions_score=81.00`, `sanctions_score=95.00`, `created_at='2023-12-01T00:00:00Z'`, `updated_at='2023-12-01T00:00:00Z'`. Row 3: `imo_number='9123456'`, `year=2023`, `month=6`, `mti_score=75.25`, `vessel_score=78.00`, `reporting_score=77.50`, `voyages_score=73.00`, `emissions_score=74.50`, `sanctions_score=90.00`, `created_at='2023-06-01T00:00:00Z'`, `updated_at='2023-06-01T00:00:00Z'`. Row 4 (partial nulls for AC8): `imo_number='9123457'`, `year=2024`, `month=1`, `mti_score=NULL`, `vessel_score=88.00`, `reporting_score=NULL`, `voyages_score=80.00`, `emissions_score=85.00`, `sanctions_score=100.00`, `created_at='2024-01-01T00:00:00Z'`, `updated_at='2024-01-01T00:00:00Z'`.

**Complexity:** S | **Dependencies:** Story 2.1

---

#### Story 6.2: MtiScoresService unit tests

**As a** developer **I want** unit tests for `MtiScoresService` **so that** all routing, error, and mapping logic is verified without a database

**Acceptance Criteria:**
- [ ] Test for each of the three routing paths (no params, year only, year+month)
- [ ] Test for ERR_101 when repository returns empty
- [ ] Test for ERR_102 when month is provided without year
- [ ] Test that null score fields map to null in the DTO (AC8)

**Tasks:**

**Task 6.2.a — Create MtiScoresServiceTest** — file: `source/src/test/java/com/example/mti/service/MtiScoresServiceTest.java`

Create class `MtiScoresServiceTest` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoresRepository repository` and `@InjectMocks MtiScoresService service`. Define a private helper method `buildMtiScore(String imo, int year, int month)` that constructs a `MtiScore` with `imoNumber=imo`, `year=year`, `month=month`, `mtiScore=new BigDecimal("85.50")`, `vesselScore=new BigDecimal("90.00")`, `reportingScore=new BigDecimal("88.75")`, `voyagesScore=new BigDecimal("82.30")`, `emissionsScore=new BigDecimal("87.60")`, `sanctionsScore=new BigDecimal("100.00")`, `createdAt=OffsetDateTime.parse("2024-01-01T00:00:00Z")`, `updatedAt=OffsetDateTime.parse("2024-01-01T00:00:00Z")`. Test method `getScores_noFilters_callsFindLatest`: given `repository.findLatest("9123456")` returns `Optional.of(buildMtiScore("9123456",2024,1))`; call `service.getScores("9123456", null, null)`; assert result `imoNumber` equals `"9123456"`, `year` equals `2024`, `month` equals `1`, `scores.mtiScore` equals `new BigDecimal("85.50")`; verify `repository.findLatest("9123456")` was called once. Test method `getScores_yearOnly_callsFindLatestByYear`: given `repository.findLatestByYear("9123456", 2023)` returns `Optional.of(buildMtiScore("9123456",2023,12))`; call `service.getScores("9123456", 2023, null)`; assert `year` equals `2023`, `month` equals `12`. Test method `getScores_yearAndMonth_callsFindByYearAndMonth`: given `repository.findByYearAndMonth("9123456", 2023, 6)` returns `Optional.of(buildMtiScore("9123456",2023,6))`; call `service.getScores("9123456", 2023, 6)`; assert `month` equals `6`. Test method `getScores_notFound_throwsERR101`: given `repository.findLatest("9999999")` returns `Optional.empty()`; call `service.getScores("9999999", null, null)`; expect `MtiException` with `errorCode == ErrorCode.ERR_101`. Test method `getScores_monthWithoutYear_throwsERR102`: call `service.getScores("9123456", null, 6)`; expect `MtiException` with `errorCode == ErrorCode.ERR_102`. Test method `getScores_nullScoreFields_mappedAsNull`: build a `MtiScore` with `mtiScore=null` and `reportingScore=null`; given `repository.findLatest("9123457")` returns it; call `service.getScores("9123457", null, null)`; assert `result.scores().mtiScore()` is `null` and `result.scores().reportingScore()` is `null`.

**Complexity:** M | **Dependencies:** Stories 4.2, 3.2

---

#### Story 6.3: MtiScoresController unit tests

**As a** developer **I want** unit tests for `MtiScoresController` using MockMvc **so that** parameter validation, response structure, and error handling are verified without a running server

**Acceptance Criteria:**
- [ ] All AC test cases (AC1–AC8) are covered
- [ ] Response body JSON structure matches the specified contract
- [ ] HTTP status codes are correct for each scenario

**Tasks:**

**Task 6.3.a — Create MtiScoresControllerTest** — file: `source/src/test/java/com/example/mti/controller/MtiScoresControllerTest.java`

Create class `MtiScoresControllerTest` annotated with `@WebMvcTest(MtiScoresController.class)` and `@Import(GlobalExceptionHandler.class)`. Declare `@Autowired MockMvc mockMvc` and `@MockBean MtiScoresService service`. Test `getMtiScores_noFilters_returns200` (AC1): stub `service.getScores("9123456", null, null)` to return a `MtiScoreDataDto` with `imoNumber="9123456"`, `year=2024`, `month=1`, scores with `mtiScore=new BigDecimal("85.50")`; perform `GET /api/v1/vessels/9123456/mti-scores`; assert status is `200`; assert JSON path `$.data.imo_number` equals `"9123456"`; assert JSON path `$.data.scores.mti_score` equals `85.50`; assert JSON path `$.meta.request_id` is not empty. Test `getMtiScores_yearFilter_returns200` (AC2): stub `service.getScores("9123456", 2023, null)` to return DTO with `year=2023`, `month=12`; perform `GET /api/v1/vessels/9123456/mti-scores?year=2023`; assert status `200`; assert `$.data.year` equals `2023`. Test `getMtiScores_yearAndMonthFilter_returns200` (AC3): perform `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6`; assert status `200`. Test `getMtiScores_imoNotFound_returns404` (AC4): stub `service.getScores("9999999", null, null)` to throw `new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO 9999999")`; perform `GET /api/v1/vessels/9999999/mti-scores`; assert status `404`; assert `$.data.error_code` equals `"ERR_101"`; assert `$.data.title` equals `"Resource Not Found"`. Test `getMtiScores_invalidImoFormat_returns400` (AC5): perform `GET /api/v1/vessels/123/mti-scores`; assert status `400`; assert `$.data.error_code` equals `"ERR_103"`. Test `getMtiScores_monthWithoutYear_returns400` (AC6): stub `service.getScores("9123456", null, 6)` to throw `new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`; perform `GET /api/v1/vessels/9123456/mti-scores?month=6`; assert status `400`; assert `$.data.error_code` equals `"ERR_102"`. Test `getMtiScores_invalidMonth_returns400` (AC7): perform `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13`; assert status `400`; assert `$.data.error_code` equals `"ERR_104"`. Test `getMtiScores_nullScores_returnsNullFields` (AC8): stub `service.getScores("9123457", null, null)` to return DTO with `mtiScore=null`; perform `GET /api/v1/vessels/9123457/mti-scores`; assert status `200`; assert `$.data.scores.mti_score` is `null`.

**Complexity:** L | **Dependencies:** Stories 4.3, 3.3

---

#### Story 6.4: Repository integration tests

**As a** developer **I want** integration tests for `MtiScoresRepository` against a real PostgreSQL container **so that** SQL correctness, RowMapper accuracy, and index usage are verified

**Acceptance Criteria:**
- [ ] Tests use Testcontainers PostgreSQL; Flyway V1 + V2 migrations are applied before tests run
- [ ] `findLatest` returns the row with the highest (year, month) for the given IMO
- [ ] `findLatestByYear` returns the row with the highest month for the given year
- [ ] `findByYearAndMonth` returns the exact matching row
- [ ] `findLatest` returns `Optional.empty()` for an unknown IMO

**Tasks:**

**Task 6.4.a — Create MtiScoresRepositoryTest** — file: `source/src/test/java/com/example/mti/repository/MtiScoresRepositoryTest.java`

Create class `MtiScoresRepositoryTest` annotated with `@SpringBootTest`, `@Testcontainers`, and `@ActiveProfiles("test")`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Declare `@DynamicPropertySource static void configureProperties(DynamicPropertyRegistry registry)` that sets `spring.datasource.url` to `postgres.getJdbcUrl()`, `spring.datasource.username` to `postgres.getUsername()`, `spring.datasource.password` to `postgres.getPassword()`. Declare `@Autowired MtiScoresRepository repository`. Test `findLatest_returnsHighestYearMonth`: call `repository.findLatest("9123456")`; assert `isPresent()` true; assert `get().getYear()` equals `2024`; assert `get().getMonth()` equals `1`; assert `get().getMtiScore()` equals `new BigDecimal("85.50")`; assert `get().getImoNumber()` equals `"9123456"`. Test `findLatestByYear_year2023_returnsDecemberRow`: call `repository.findLatestByYear("9123456", 2023)`; assert `get().getYear()` equals `2023`; assert `get().getMonth()` equals `12`; assert `get().getMtiScore()` equals `new BigDecimal("80.00")`. Test `findByYearAndMonth_2023_6_returnsJuneRow`: call `repository.findByYearAndMonth("9123456", 2023, 6)`; assert `get().getMonth()` equals `6`; assert `get().getMtiScore()` equals `new BigDecimal("75.25")`. Test `findLatest_unknownImo_returnsEmpty`: call `repository.findLatest("0000000")`; assert `isEmpty()` true.

**Complexity:** M | **Dependencies:** Stories 2.3, 6.1

---

#### Story 6.5: Full API integration tests

**As a** developer **I want** end-to-end integration tests that start the full Spring Boot application against a Testcontainers PostgreSQL database **so that** the complete request/response cycle is verified

**Acceptance Criteria:**
- [ ] All AC1–AC8 scenarios pass as HTTP-level assertions
- [ ] `X-Request-ID` response header is present on all responses
- [ ] Rate limit filter does not block requests within the 100/min threshold during tests

**Tasks:**

**Task 6.5.a — Create MtiScoresIntegrationTest** — file: `source/src/test/java/com/example/mti/integration/MtiScoresIntegrationTest.java`

Create class `MtiScoresIntegrationTest` annotated with `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, `@Testcontainers`, and `@ActiveProfiles("test")`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Declare the `@DynamicPropertySource` method as in Story 6.4.a. Declare `@Autowired TestRestTemplate restTemplate` and `@LocalServerPort int port`. Helper method `url(String path)` returns `"http://localhost:" + port + path`. Test `ac1_getLatestScores`: call `GET /api/v1/vessels/9123456/mti-scores`; assert HTTP 200; assert `body.data.imo_number == "9123456"`; assert `body.data.year == 2024`; assert `body.data.month == 1`; assert `body.data.scores.mti_score == 85.50`; assert response header `X-Request-ID` is not blank. Test `ac2_getByYear`: call `GET /api/v1/vessels/9123456/mti-scores?year=2023`; assert HTTP 200; assert `body.data.year == 2023`; assert `body.data.month == 12`. Test `ac3_getByYearAndMonth`: call `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6`; assert HTTP 200; assert `body.data.month == 6`. Test `ac4_imoNotFound`: call `GET /api/v1/vessels/9999999/mti-scores`; assert HTTP 404; assert `body.data.error_code == "ERR_101"`. Test `ac5_invalidImoFormat`: call `GET /api/v1/vessels/123/mti-scores`; assert HTTP 400; assert `body.data.error_code == "ERR_103"`. Test `ac6_monthWithoutYear`: call `GET /api/v1/vessels/9123456/mti-scores?month=6`; assert HTTP 400; assert `body.data.error_code == "ERR_102"`. Test `ac7_invalidMonthValue`: call `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13`; assert HTTP 400; assert `body.data.error_code == "ERR_104"`. Test `ac8_partialNullScores`: call `GET /api/v1/vessels/9123457/mti-scores`; assert HTTP 200; assert `body.data.scores.mti_score` is null in JSON; assert `body.data.scores.vessel_score == 88.00`.

**Complexity:** M | **Dependencies:** Stories 4.3, 6.1

---

### Epic 7: Documentation & OpenAPI

**Goal:** Expose interactive API documentation and finalize the project README
**Priority:** Low | **Estimated Complexity:** S

---

#### Story 7.1: OpenAPI configuration

**As a** developer **I want** SpringDoc OpenAPI metadata configured **so that** the Swagger UI displays correct API title, version, and description

**Acceptance Criteria:**
- [ ] `OpenApiConfig` provides an `OpenAPI` bean with title "MTI Scores API", version "1.0.0"
- [ ] Swagger UI accessible at `/swagger-ui.html`
- [ ] OpenAPI JSON accessible at `/v3/api-docs`

**Tasks:**

**Task 7.1.a — Create OpenApiConfig** — file: `source/src/main/java/com/example/mti/config/OpenApiConfig.java`

Create class `OpenApiConfig` annotated with `@Configuration`. Declare a `@Bean` method `openAPI()` returning `io.swagger.v3.oas.models.OpenAPI`. Inside the method, construct and return `new OpenAPI().info(new Info().title("MTI Scores API").version("1.0.0").description("API for retrieving Maritime Transportation Indicator scores by vessel IMO number"))`. No server URL override is needed — SpringDoc auto-discovers the server from the servlet context.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     string   Required   7-digit vessel IMO number; must match ^[0-9]{7}$

Query Parameters:
  year    integer  Optional   Year filter; must be between 2000 and 2100 inclusive
  month   integer  Optional   Month filter (1–12); requires year to also be present

Request Headers:
  (none required)

Response Headers:
  X-Request-ID: <uuid>   Unique request correlation ID set by RequestIdFilter

Success Response — 200:
  meta.request_id            string    UUID v4 correlation ID
  meta.request_timestamp     string    ISO-8601 UTC timestamp of the request
  data.imo_number            string    7-digit IMO number
  data.year                  integer   Year of the returned scores
  data.month                 integer   Month of the returned scores
  data.scores.mti_score      number    MTI composite score (nullable)
  data.scores.vessel_score   number    Vessel sub-score (nullable)
  data.scores.reporting_score number   Reporting sub-score (nullable)
  data.scores.voyages_score  number    Voyages sub-score (nullable)
  data.scores.emissions_score number   Emissions sub-score (nullable)
  data.scores.sanctions_score number   Sanctions sub-score (nullable)
  data.metadata.created_at   string    ISO-8601 row creation timestamp
  data.metadata.updated_at   string    ISO-8601 row last-update timestamp

Error Response — 4XX / 5XX:
  meta.request_id            string    UUID v4 correlation ID
  meta.request_timestamp     string    ISO-8601 UTC timestamp
  data.error_code            string    Machine-readable error code
  data.title                 string    Human-readable error category
  data.message               string    Detailed error description

Error Code Reference:
  ERR_101   404   No MTI scores found for the given IMO / year / month combination
  ERR_102   400   Month parameter provided without year parameter
  ERR_103   400   IMO number does not match ^[0-9]{7}$
  ERR_104   400   Year is outside [2000,2100] or month is outside [1,12]
  ERR_105   500   Database connection failure or unexpected server error
  ERR_106   429   Rate limit exceeded (100 req/min per IP)
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 latency < 100 ms; index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` must exist |
| Logging | SLF4J + Logback; MDC field `requestId` on every log line; INFO for normal operations, WARN for business errors, ERROR for unexpected failures |
| Metrics | Spring Boot Actuator `/actuator/health` for liveness probe; extend with Micrometer if Prometheus scraping is required |
| Security | IMO validated by `@Pattern`; year/month by `@Min`/`@Max`; all SQL via JdbcTemplate parameterized queries; no stack traces in error responses |
| Rate Limiting | Token-bucket via Bucket4j; 100 tokens/minute per remote IP; 429 response with ERR_106 on exhaustion |
| Testing | ≥80% line coverage; unit tests with Mockito; integration tests with Testcontainers PostgreSQL 15 |
| Health / Docs | `/actuator/health` → `{"status":"UP"}`; Swagger UI at `/swagger-ui.html`; OpenAPI JSON at `/v3/api-docs` |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `MtiScoresController` | `MtiScoresService` | Delegates all business logic |
| `MtiScoresController` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request attribute to populate `meta.request_id` |
| `MtiScoresService` | `MtiScoresRepository` | Issues DB queries |
| `MtiScoresService` | `ErrorCode.ERR_101`, `ErrorCode.ERR_102` | Throws typed errors |
| `GlobalExceptionHandler` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request attribute in error responses |
| `GlobalExceptionHandler` | `ErrorCode` | Maps exception types to codes and HTTP statuses |
| `GlobalExceptionHandler` | `ApiResponse`, `ErrorDataDto`, `MetaDto` | Constructs error response body |
| `RateLimitFilter` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request ID for error response and logging |
| `RateLimitFilter` | `RateLimitConfig` (ConcurrentHashMap bean) | Obtains per-IP bucket store |
| `MtiScoresRepository` | `JdbcTemplate` | Issues parameterized SQL |
| `RequestIdFilter` | `MDC` (SLF4J) | Propagates request ID to log context |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Maven project and entry point must exist before any other code compiles
2. **Story 1.2** — Configuration must be in place before Spring context can start
3. **Story 2.1** — Schema migration must run before repository or integration tests
4. **Story 2.2** — `MtiScore` model needed by repository RowMapper
5. **Story 2.3** — Repository needed by service
6. **Story 3.1** — `RequestIdFilter` and its constants (`REQUEST_ID_ATTR`) are referenced by controller and exception handler
7. **Story 3.2** — `ErrorCode` enum and `MtiException` are referenced by service and exception handler
8. **Story 3.3** — `GlobalExceptionHandler` depends on Stories 3.1 and 3.2
9. **Story 4.1** — DTOs are needed by service and controller
10. **Story 4.2** — Service depends on repository, DTOs, and error codes
11. **Story 4.3** — Controller depends on service, filter constants, and DTOs
12. **Story 5.1** — Rate limiting can be added after the main flow is working
13. **Story 6.1** — Seed data migration must exist before integration tests run
14. **Stories 6.2–6.5** — Tests run once the components under test are implemented
15. **Story 7.1** — OpenAPI configuration is purely additive; can be done last

> Stories 6.2 and 6.3 can be developed in parallel once Stories 4.2 and 4.3 are complete.
> Stories 6.4 and 6.5 can be developed in parallel with each other but require Story 6.1.
> Story 1.3 (Docker) can be done in parallel with Stories 2–5.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `ApiResponse<T>` | `MetaDto meta`, `T data` | `meta`, `data` | Generic wrapper for all responses |
| `MetaDto` | `String requestId`, `String requestTimestamp` | `request_id`, `request_timestamp` | Set by controller and exception handler |
| `MtiScoreDataDto` | `String imoNumber`, `Integer year`, `Integer month`, `ScoresDto scores`, `MetadataDto metadata` | `imo_number`, `year`, `month`, `scores`, `metadata` | Success response data |
| `ScoresDto` | `BigDecimal mtiScore`, `BigDecimal vesselScore`, `BigDecimal reportingScore`, `BigDecimal voyagesScore`, `BigDecimal emissionsScore`, `BigDecimal sanctionsScore` | `mti_score`, `vessel_score`, `reporting_score`, `voyages_score`, `emissions_score`, `sanctions_score` | All fields nullable; must serialize as `null` not omitted |
| `MetadataDto` | `String createdAt`, `String updatedAt` | `created_at`, `updated_at` | ISO-8601 strings |
| `ErrorDataDto` | `String errorCode`, `String title`, `String message` | `error_code`, `title`, `message` | Error response data |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | Yes (prod) | `jdbc:postgresql://localhost:5432/mtidb` | Full JDBC URL to PostgreSQL |
| `DATABASE_USERNAME` | Yes (prod) | `mti` | PostgreSQL username |
| `DATABASE_PASSWORD` | Yes (prod) | `mti` | PostgreSQL password |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | — |
| `year` | INTEGER | No | — |
| `month` | INTEGER | No | — |
| `mti_score` | NUMERIC(5,2) | Yes | — |
| `vessel_score` | NUMERIC(5,2) | Yes | — |
| `reporting_score` | NUMERIC(5,2) | Yes | — |
| `voyages_score` | NUMERIC(5,2) | Yes | — |
| `emissions_score` | NUMERIC(5,2) | Yes | — |
| `sanctions_score` | NUMERIC(5,2) | Yes | — |
| `created_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |

Additional constraints:
- Unique constraint `uq_imo_year_month` on `(imo_number, year, month)` — prevents duplicate entries
- Index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` — required for performance

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| NULL BigDecimal scores omitted by Jackson | M | H | Use `@JsonInclude(Include.ALWAYS)` on `ScoresDto`; cover with AC8 integration test |
| `ConstraintViolationException` not caught by `@RestControllerAdvice` | M | M | Ensure controller class has `@Validated`; add `spring.mvc.throw-exception-if-no-handler-found=true` if needed |
| Flyway migration fails on existing database with data | L | H | Wrap V1 DDL in `IF NOT EXISTS`; treat V2 seed as dev-only (skip in prod via Flyway profiles) |
| Rate limit map grows unbounded with unique IPs | M | M | For production, replace `ConcurrentHashMap` with a Caffeine cache with TTL; acceptable for single-instance deployment |
| `OffsetDateTime` not mapped by JDBC driver | L | H | Verify PostgreSQL JDBC driver version ≥ 42.3; use `rs.getObject("col", OffsetDateTime.class)` as specified |
| Month/year validation not triggered for non-integer inputs | L | M | Spring auto-converts; invalid types (e.g., `?year=abc`) produce `MethodArgumentTypeMismatchException` — add handler in `GlobalExceptionHandler` mapping to ERR_104 |

---

## DEFINITION OF DONE

### For Each Story
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing (target: ≥80% coverage)
- [ ] Integration tests passing
- [ ] No new linting errors
- [ ] Acceptance criteria verified

### For the Release
- [ ] All stories complete
- [ ] Performance targets verified under load (p95 < 100 ms with index in place)
- [ ] Security review passed (parameterized queries confirmed, no stack traces in responses)
- [ ] API documentation updated (Swagger UI accessible at `/swagger-ui.html`)
- [ ] Docker image builds and runs locally via `docker compose up`
- [ ] Environment variables documented in README

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven scaffolding; nothing else compiles without it
2. **Story 1.2** — Application configuration; Spring context requires it
3. **Story 1.3** — Docker setup; can run in parallel with Stories 2–5
4. **Story 2.1** — Schema migration; database must exist before repository tests
5. **Story 2.2** — `MtiScore` model; required by `MtiScoresRepository` RowMapper
6. **Story 2.3** — `MtiScoresRepository`; required by `MtiScoresService`
7. **Story 3.1** — `RequestIdFilter`; its `REQUEST_ID_ATTR` constant is needed by controller and exception handler
8. **Story 3.2** — `ErrorCode` + `MtiException`; needed by service and exception handler
9. **Story 3.3** — `GlobalExceptionHandler`; depends on Stories 3.1 and 3.2
10. **Story 4.1** — All DTOs; needed by service and controller
11. **Story 4.2** — `MtiScoresService`; depends on Stories 2.3, 3.2, 4.1
12. **Story 4.3** — `MtiScoresController`; depends on Stories 3.1, 3.3, 4.1, 4.2
13. **Story 5.1** — Rate limiting; additive after the main request flow works
14. **Story 6.1** — Seed data migration; required before integration tests
15. **Story 6.2** — `MtiScoresServiceTest`; parallel with Story 6.3
16. **Story 6.3** — `MtiScoresControllerTest`; parallel with Story 6.2
17. **Story 6.4** — `MtiScoresRepositoryTest`; parallel with Story 6.5; requires Story 6.1
18. **Story 6.5** — `MtiScoresIntegrationTest`; parallel with Story 6.4; requires Story 6.1
19. **Story 7.1** — OpenAPI config; purely additive, can be done at any point after Story 1.1

> Parallel tracks:
> - Track A: Stories 1.1 → 2.1 → 2.2 → 2.3 → 4.2 → 6.2, 6.4
> - Track B (after 1.1): Stories 3.1 → 3.2 → 3.3 → 4.1 → 4.3 → 6.3, 6.5
> - Track C (independent): Stories 1.3, 7.1, 5.1
