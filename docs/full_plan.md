# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The MTI Scores API provides a single REST endpoint (`GET /api/v1/vessels/{imo}/mti-scores`) to retrieve Maritime Transportation Indicator (MTI) scores for vessels identified by a 7-digit IMO number. Clients may optionally filter results by year and/or year+month, with well-defined business rules and error codes for each combination. The implementation uses Spring Boot 3.2 over PostgreSQL with explicit JDBC queries, Flyway schema migrations, per-request UUID tracing, in-process Bucket4j rate limiting, and structured JSON error responses. The goal is a fully self-contained, containerised micro-service that is testable end-to-end via Testcontainers.

---

## TECHNICAL ANALYSIS

### Recommended Stack (Greenfield)

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 17 | LTS release; records, sealed classes, modern syntax |
| Build | Maven 3.9 | Ubiquitous in enterprise Java; rich plugin ecosystem |
| Framework | Spring Boot 3.2 | Production-grade web framework; actuator; auto-configuration |
| Database | PostgreSQL 15 | ACID-compliant; strong numeric types; industry standard |
| Migrations | Flyway 10 | SQL-first migrations; native Spring Boot integration |
| Rate Limiting | Bucket4j 8.x | Token-bucket algorithm; in-process, no external cache needed for v1 |
| Testing | JUnit 5, Mockito, Testcontainers | Unit + integration; real DB via Docker |
| Containerisation | Docker + docker-compose | Local reproducibility; CI/CD readiness |
| API Docs | SpringDoc OpenAPI 2.x | Auto-generates Swagger UI from annotations; Spring Boot 3 compatible |

### Project Structure

```
mti-scores-api/
└── source/
    ├── src/
    │   ├── main/
    │   │   ├── java/com/example/mti/
    │   │   │   ├── MtiScoresApplication.java
    │   │   │   ├── config/
    │   │   │   ├── constant/
    │   │   │   │   └── ErrorCode.java
    │   │   │   ├── controller/
    │   │   │   │   └── VesselController.java
    │   │   │   ├── service/
    │   │   │   │   └── MtiScoresService.java
    │   │   │   ├── repository/
    │   │   │   │   └── MtiScoresRepository.java
    │   │   │   ├── model/
    │   │   │   │   └── MtiScoreRecord.java
    │   │   │   ├── dto/
    │   │   │   │   ├── MetaDto.java
    │   │   │   │   ├── ScoresDto.java
    │   │   │   │   ├── MetadataDto.java
    │   │   │   │   ├── MtiScoreDataDto.java
    │   │   │   │   ├── SuccessResponseDto.java
    │   │   │   │   ├── ErrorDataDto.java
    │   │   │   │   └── ErrorResponseDto.java
    │   │   │   ├── filter/
    │   │   │   │   ├── RequestIdFilter.java
    │   │   │   │   └── RateLimitFilter.java
    │   │   │   └── exception/
    │   │   │       ├── MtiApiException.java
    │   │   │       └── GlobalExceptionHandler.java
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       └── db/migration/
    │   │           ├── V1__create_mti_scores_history.sql
    │   │           └── V2__seed_test_data.sql
    │   └── test/
    │       ├── java/com/example/mti/
    │       │   ├── repository/
    │       │   │   └── MtiScoresRepositoryTest.java
    │       │   ├── service/
    │       │   │   └── MtiScoresServiceTest.java
    │       │   └── integration/
    │       │       └── MtiScoresIntegrationTest.java
    │       └── resources/
    │           └── application-test.yml
    ├── Dockerfile
    ├── docker-compose.yml
    └── pom.xml
```

### Integration Points

- **PostgreSQL database**: Single table `mti_scores_history` queried via Spring JDBC (`JdbcTemplate`). No ORM; queries are kept explicit for performance transparency.
- **No external auth service in v1**: Rate limiting uses an `X-API-Key` request header as the per-client identifier; presence check only in this phase.

### Technical Constraints

- IMO number must match regex `^[0-9]{7}$`
- Year must be in range 2000–2100; month in range 1–12
- Month parameter requires year parameter (ERR_102)
- Database index required on `(imo_number, year DESC, month DESC)` for query performance
- Rate limit: 100 requests per minute per API key (configurable via `RATE_LIMIT_RPM` env var)
- All score columns are nullable (NUMERIC(5,2))
- `request_id` is UUID v4, generated per request, propagated via MDC through all log statements

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.mti` | **Group ID:** `com.example` | **Artifact ID:** `mti-scores-api`

### Overview

The backend implements a single REST endpoint that validates inputs, routes to one of three SQL query strategies, maps results to a versioned JSON response shape, and returns structured error responses for all failure paths. The layered architecture separates the HTTP adapter (controller), business rules (service), and data access (repository), with a filter chain handling cross-cutting concerns before the controller is reached.

---

### Epic 1: Project Scaffolding & Infrastructure

**Goal:** Establish a runnable Spring Boot application with database connectivity, Flyway migrations, Docker support, and the base schema.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Bootstrap Maven Project

**As a** developer **I want** a Maven project with all required dependencies declared **so that** all subsequent implementation tasks have a consistent, buildable foundation.

**Background for implementer:** Spring Boot 3.2 requires Java 17+. The BOM (`spring-boot-starter-parent`) pins all Spring dependency versions so explicit version numbers are only needed for non-Spring artifacts. Bucket4j `8.7.0` is the in-process rate limiting library — use `bucket4j-core` (no Redis module needed for v1). SpringDoc OpenAPI `2.3.0` is the Spring Boot 3-compatible fork; do NOT use the older `springfox` library which is incompatible with Spring Boot 3.

**Acceptance Criteria:**
- [ ] `source/pom.xml` exists with group `com.example`, artifact `mti-scores-api`, version `1.0.0-SNAPSHOT`
- [ ] All listed dependencies are present and the project compiles cleanly with `mvn compile`
- [ ] Spring Boot parent version is `3.2.5`

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `source/pom.xml`

Create `source/pom.xml` with parent `org.springframework.boot:spring-boot-starter-parent:3.2.5`. Set `groupId` to `com.example`, `artifactId` to `mti-scores-api`, `version` to `1.0.0-SNAPSHOT`, Java `source`/`target` property `java.version` to `17`. Add dependencies: `spring-boot-starter-web` (compile), `spring-boot-starter-jdbc` (compile), `spring-boot-starter-validation` (compile), `spring-boot-starter-actuator` (compile), `org.flywaydb:flyway-core` (managed by parent — no explicit version needed), `org.postgresql:postgresql` (runtime scope), `com.bucket4j:bucket4j-core:8.7.0` (compile scope), `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0` (compile scope). Add test-scoped dependencies: `spring-boot-starter-test`, `org.testcontainers:junit-jupiter:1.19.7`, `org.testcontainers:postgresql:1.19.7`. Add `spring-boot-maven-plugin` in the `<build><plugins>` section with `repackage` goal.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Application Entry Point & Configuration

**As a** developer **I want** `MtiScoresApplication.java` and `application.yml` **so that** the Spring Boot application starts and connects to the database.

**Background for implementer:** `application.yml` must reference environment variables for all infrastructure coordinates so the service is 12-factor compliant. Default values (e.g., `${DATABASE_HOST:localhost}`) allow local development without setting env vars. Flyway auto-runs migrations at startup when `spring.flyway.enabled=true` and `spring.flyway.locations` points to the migration classpath.

**Acceptance Criteria:**
- [ ] `MtiScoresApplication.java` exists, annotated `@SpringBootApplication`, compiles and starts
- [ ] `application.yml` references env vars `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD`, `RATE_LIMIT_RPM`
- [ ] Application starts without errors when env vars and PostgreSQL are available

**Tasks:**

**Task 1.2.a — Create MtiScoresApplication** — file: `source/src/main/java/com/example/mti/MtiScoresApplication.java`

Create class `MtiScoresApplication` in package `com.example.mti`, annotated with `@SpringBootApplication`. Add a `public static void main(String[] args)` method that calls `SpringApplication.run(MtiScoresApplication.class, args)`. This is the sole application entry point; no additional configuration is needed here.

**Task 1.2.b — Create application.yml** — file: `source/src/main/resources/application.yml`

Create `application.yml` with these exact keys: `server.port: 8080`; `spring.datasource.url: jdbc:postgresql://${DATABASE_HOST:localhost}:${DATABASE_PORT:5432}/${DATABASE_NAME:mti_db}`; `spring.datasource.username: ${DATABASE_USER:mti_user}`; `spring.datasource.password: ${DATABASE_PASSWORD:mti_pass}`; `spring.datasource.driver-class-name: org.postgresql.Driver`; `spring.flyway.enabled: true`; `spring.flyway.locations: classpath:db/migration`; `management.endpoints.web.exposure.include: health,info`; `springdoc.api-docs.path: /api-docs`; `springdoc.swagger-ui.path: /swagger-ui.html`; `app.rate-limit.requests-per-minute: ${RATE_LIMIT_RPM:100}`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 1.3: Database Migration — Create mti_scores_history Table

**As a** developer **I want** a Flyway migration that creates the `mti_scores_history` table **so that** the application has the required schema at startup.

**Background for implementer:** Flyway applies SQL scripts in version order from `classpath:db/migration`. The mandatory naming convention is `V<version>__<description>.sql` (two underscores). The composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is critical for the three query patterns in section 7 of the PRD — without it, queries degrade to full-table scans. All six score columns are `NUMERIC(5,2)` and nullable per PRD AC8 (partial score data). V2 seeds known rows so integration tests have predictable data without managing test fixtures separately.

**Acceptance Criteria:**
- [ ] `V1__create_mti_scores_history.sql` creates table `mti_scores_history` with all required columns
- [ ] Unique constraint `uq_mti_scores_imo_year_month` exists on `(imo_number, year, month)`
- [ ] Index `idx_mti_scores_imo_year_month` exists on `(imo_number, year DESC, month DESC)`
- [ ] `V2__seed_test_data.sql` inserts 4 rows covering all test scenarios
- [ ] Flyway runs both migrations at application startup without errors

**Tasks:**

**Task 1.3.a — Create Flyway migration V1** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Create SQL migration that executes `CREATE TABLE mti_scores_history` with columns: `id BIGSERIAL PRIMARY KEY`, `imo_number VARCHAR(7) NOT NULL`, `year INTEGER NOT NULL`, `month INTEGER NOT NULL`, `mti_score NUMERIC(5,2) NULL`, `vessel_score NUMERIC(5,2) NULL`, `reporting_score NUMERIC(5,2) NULL`, `voyages_score NUMERIC(5,2) NULL`, `emissions_score NUMERIC(5,2) NULL`, `sanctions_score NUMERIC(5,2) NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Add `ALTER TABLE mti_scores_history ADD CONSTRAINT uq_mti_scores_imo_year_month UNIQUE (imo_number, year, month)`. Add `CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history(imo_number, year DESC, month DESC)`.

**Task 1.3.b — Create seed data migration V2** — file: `source/src/main/resources/db/migration/V2__seed_test_data.sql`

Create SQL migration that inserts exactly four rows into `mti_scores_history`. Row 1: `imo_number='9123456', year=2024, month=1, mti_score=85.50, vessel_score=90.00, reporting_score=88.75, voyages_score=82.30, emissions_score=87.60, sanctions_score=100.00, created_at='2024-01-01T00:00:00Z', updated_at='2024-01-01T00:00:00Z'`. Row 2: `imo_number='9123456', year=2023, month=6, mti_score=80.00, vessel_score=85.00, reporting_score=82.00, voyages_score=78.00, emissions_score=81.00, sanctions_score=95.00, created_at='2023-06-01T00:00:00Z', updated_at='2023-06-01T00:00:00Z'`. Row 3: `imo_number='9123456', year=2023, month=3, mti_score=79.00, vessel_score=84.00, reporting_score=81.00, voyages_score=77.00, emissions_score=80.00, sanctions_score=94.00, created_at='2023-03-01T00:00:00Z', updated_at='2023-03-01T00:00:00Z'`. Row 4: `imo_number='9999998', year=2024, month=1, mti_score=NULL, vessel_score=NULL, reporting_score=NULL, voyages_score=NULL, emissions_score=NULL, sanctions_score=NULL, created_at='2024-01-01T00:00:00Z', updated_at='2024-01-01T00:00:00Z'`.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 1.4: Docker & docker-compose Setup

**As a** developer **I want** a `Dockerfile` and `docker-compose.yml` **so that** the full application stack (app + PostgreSQL) can run locally with a single command.

**Acceptance Criteria:**
- [ ] `Dockerfile` uses a two-stage build; final image contains only the JRE and the fat JAR
- [ ] `docker-compose.yml` starts both `db` (PostgreSQL 15) and `app` services
- [ ] `docker-compose up` succeeds and `GET http://localhost:8080/actuator/health` returns `{"status":"UP"}`

**Tasks:**

**Task 1.4.a — Create Dockerfile** — file: `source/Dockerfile`

Create a two-stage `Dockerfile`. Stage 1 named `builder`: base image `maven:3.9-eclipse-temurin-17`, set `WORKDIR /build`, copy `pom.xml` then `src/`, run `mvn -q package -DskipTests` to produce the fat JAR. Stage 2 named `runtime`: base image `eclipse-temurin:17-jre-jammy`, set `WORKDIR /app`, copy `--from=builder /build/target/mti-scores-api-1.0.0-SNAPSHOT.jar app.jar`, `EXPOSE 8080`, set `ENTRYPOINT ["java", "-jar", "app.jar"]`.

**Task 1.4.b — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create `docker-compose.yml` at compose file format version `3.9`. Define service `db`: image `postgres:15-alpine`, environment `POSTGRES_DB=mti_db`, `POSTGRES_USER=mti_user`, `POSTGRES_PASSWORD=mti_pass`, ports `5432:5432`, named volume `pgdata:/var/lib/postgresql/data`. Define service `app`: build context `.` (Dockerfile in same directory), `depends_on: [db]`, environment `DATABASE_HOST=db`, `DATABASE_PORT=5432`, `DATABASE_NAME=mti_db`, `DATABASE_USER=mti_user`, `DATABASE_PASSWORD=mti_pass`, `RATE_LIMIT_RPM=100`, ports `8080:8080`. Declare top-level `volumes: pgdata:`.

**Complexity:** S | **Dependencies:** Story 1.2

---

### Epic 2: Domain Model & Data Layer

**Goal:** Implement the domain record, error code enum, RowMapper, and repository with the three query strategies from the PRD.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Domain Model

**As a** developer **I want** `MtiScoreRecord` and `ErrorCode` **so that** the data layer and error handling have concrete Java types to work with.

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` is a Java record with 12 components matching the `mti_scores_history` columns; all score components use boxed `Double`
- [ ] `ErrorCode` enum declares all five error codes with code string, HTTP status integer, and title string

**Tasks:**

**Task 2.1.a — Create MtiScoreRecord** — file: `source/src/main/java/com/example/mti/model/MtiScoreRecord.java`

Create `MtiScoreRecord` as a Java record (NOT a JPA entity — this project uses JDBC) in package `com.example.mti.model`. Record components in order: `Long id`, `String imoNumber`, `Integer year`, `Integer month`, `Double mtiScore`, `Double vesselScore`, `Double reportingScore`, `Double voyagesScore`, `Double emissionsScore`, `Double sanctionsScore`, `java.time.OffsetDateTime createdAt`, `java.time.OffsetDateTime updatedAt`. All six score components must be boxed `Double` (not primitive `double`) to allow null from the database.

**Task 2.1.b — Create ErrorCode enum** — file: `source/src/main/java/com/example/mti/constant/ErrorCode.java`

Create `ErrorCode` as a Java enum in package `com.example.mti.constant` with three private final fields: `String code`, `int httpStatus`, `String title`. Constructor takes all three. Define constants: `ERR_101("ERR_101", 404, "Resource Not Found")`, `ERR_102("ERR_102", 400, "Invalid Parameters")`, `ERR_103("ERR_103", 400, "Invalid IMO Format")`, `ERR_104("ERR_104", 400, "Invalid Date Range")`, `ERR_105("ERR_105", 500, "Internal Server Error")`. Provide public getters `getCode()`, `getHttpStatus()`, `getTitle()`. The enum is not annotated; it is a plain Java enum.

**Complexity:** S | **Dependencies:** Story 1.3

---

#### Story 2.2: MtiScores Repository

**As a** developer **I want** `MtiScoresRepository` with a `RowMapper` and three query methods **so that** the service layer can retrieve scores from the database without writing raw SQL.

**Background for implementer:** Spring JDBC's `JdbcTemplate` is used instead of JPA to keep the three SQL queries explicit and identical to those specified in section 7 of the PRD. Using `rs.getObject("mti_score", Double.class)` is mandatory — `rs.getDouble("mti_score")` would silently return `0.0` for SQL NULL, violating AC8. Each query variant maps directly to one of the three PRD SQL patterns.

**Acceptance Criteria:**
- [ ] `MtiScoresRepository` is annotated `@Repository` and has `JdbcTemplate` injected via constructor
- [ ] Inner static class `MtiScoresRowMapper` implements `RowMapper<MtiScoreRecord>` and maps all 12 columns using `getObject` for nullable `Double` fields
- [ ] `findLatest(String imoNumber)` returns `Optional<MtiScoreRecord>` using ORDER BY year DESC, month DESC LIMIT 1
- [ ] `findByYear(String imoNumber, int year)` returns `Optional<MtiScoreRecord>` using ORDER BY month DESC LIMIT 1
- [ ] `findByYearAndMonth(String imoNumber, int year, int month)` returns `Optional<MtiScoreRecord>`

**Tasks:**

**Task 2.2.a — Create MtiScoresRepository** — file: `source/src/main/java/com/example/mti/repository/MtiScoresRepository.java`

Create class `MtiScoresRepository` in package `com.example.mti.repository`, annotated `@Repository`. Inject `JdbcTemplate jdbcTemplate` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresRepository.class)`.

Implement method `findLatest(String imoNumber)` with return type `Optional<MtiScoreRecord>`: log at DEBUG `log.debug("Querying latest MTI scores for imo={}", imoNumber)`; execute `jdbcTemplate.query("SELECT id, imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1", new MtiScoresRowMapper(), imoNumber)`; return `results.stream().findFirst()`.

Implement method `findByYear(String imoNumber, int year)` with return type `Optional<MtiScoreRecord>`: log at DEBUG `log.debug("Querying MTI scores for imo={} year={}", imoNumber, year)`; execute same SELECT with `WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1` passing `imoNumber, year`; return `results.stream().findFirst()`.

Implement method `findByYearAndMonth(String imoNumber, int year, int month)` with return type `Optional<MtiScoreRecord>`: log at DEBUG `log.debug("Querying MTI scores for imo={} year={} month={}", imoNumber, year, month)`; execute same SELECT with `WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1` passing `imoNumber, year, month`; return `results.stream().findFirst()`.

Declare static inner class `MtiScoresRowMapper` implementing `RowMapper<MtiScoreRecord>`. Its `mapRow(ResultSet rs, int rowNum)` method constructs and returns a new `MtiScoreRecord` mapping columns as: `rs.getLong("id")`, `rs.getString("imo_number")`, `rs.getInt("year")`, `rs.getInt("month")`, `rs.getObject("mti_score", Double.class)`, `rs.getObject("vessel_score", Double.class)`, `rs.getObject("reporting_score", Double.class)`, `rs.getObject("voyages_score", Double.class)`, `rs.getObject("emissions_score", Double.class)`, `rs.getObject("sanctions_score", Double.class)`, `rs.getObject("created_at", OffsetDateTime.class)`, `rs.getObject("updated_at", OffsetDateTime.class)`.

**Complexity:** M | **Dependencies:** Story 2.1

---

### Epic 3: DTOs & Response Mapping

**Goal:** Implement all DTO classes matching the API response structure defined in section 3 and the OpenAPI spec in section 6 of the PRD.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 3.1: Response DTOs

**As a** developer **I want** DTO classes matching the API response structure **so that** the controller serialises clean JSON matching the OpenAPI spec.

**Background for implementer:** Java records are used for all DTOs — they are immutable, require no Lombok, and Jackson 2.15+ serialises them natively. Use `@JsonProperty` on record components wherever the JSON field name differs from the Java identifier (snake_case JSON vs camelCase Java). `MetaDto` is shared by both `SuccessResponseDto` and `ErrorResponseDto`. Score fields use boxed `Double` so Jackson serialises SQL NULL as JSON `null`.

**Acceptance Criteria:**
- [ ] `MetaDto` is a record with `requestId` serialised as `request_id` and `requestTimestamp` as `request_timestamp`
- [ ] `ScoresDto` is a record with all six nullable `Double` fields using snake_case JSON names
- [ ] `MetadataDto` is a record with `createdAt` and `updatedAt` as ISO 8601 strings
- [ ] `MtiScoreDataDto` wraps `imoNumber`, `year`, `month`, `ScoresDto scores`, `MetadataDto metadata`
- [ ] `SuccessResponseDto` wraps `MetaDto meta` and `MtiScoreDataDto data`
- [ ] `ErrorDataDto` wraps `errorCode`, `title`, `message` with snake_case JSON names
- [ ] `ErrorResponseDto` wraps `MetaDto meta` and `ErrorDataDto data`

**Tasks:**

**Task 3.1.a — Create MetaDto** — file: `source/src/main/java/com/example/mti/dto/MetaDto.java`

Create Java record `MetaDto` in package `com.example.mti.dto` with two components: `@JsonProperty("request_id") String requestId` and `@JsonProperty("request_timestamp") String requestTimestamp`. Import `com.fasterxml.jackson.annotation.JsonProperty`. No class-level annotations required.

**Task 3.1.b — Create ScoresDto** — file: `source/src/main/java/com/example/mti/dto/ScoresDto.java`

Create Java record `ScoresDto` in package `com.example.mti.dto` with six components, all boxed `Double` (nullable): `@JsonProperty("mti_score") Double mtiScore`, `@JsonProperty("vessel_score") Double vesselScore`, `@JsonProperty("reporting_score") Double reportingScore`, `@JsonProperty("voyages_score") Double voyagesScore`, `@JsonProperty("emissions_score") Double emissionsScore`, `@JsonProperty("sanctions_score") Double sanctionsScore`.

**Task 3.1.c — Create MetadataDto** — file: `source/src/main/java/com/example/mti/dto/MetadataDto.java`

Create Java record `MetadataDto` in package `com.example.mti.dto` with two `String` components: `@JsonProperty("created_at") String createdAt` and `@JsonProperty("updated_at") String updatedAt`. These hold ISO 8601 strings formatted from `OffsetDateTime.toString()`.

**Task 3.1.d — Create MtiScoreDataDto** — file: `source/src/main/java/com/example/mti/dto/MtiScoreDataDto.java`

Create Java record `MtiScoreDataDto` in package `com.example.mti.dto` with components: `@JsonProperty("imo_number") String imoNumber`, `Integer year`, `Integer month`, `ScoresDto scores`, `MetadataDto metadata`.

**Task 3.1.e — Create SuccessResponseDto** — file: `source/src/main/java/com/example/mti/dto/SuccessResponseDto.java`

Create Java record `SuccessResponseDto` in package `com.example.mti.dto` with components: `MetaDto meta` and `MtiScoreDataDto data`. No additional annotations needed; Jackson serialises records natively in Spring Boot 3.2.

**Task 3.1.f — Create ErrorDataDto** — file: `source/src/main/java/com/example/mti/dto/ErrorDataDto.java`

Create Java record `ErrorDataDto` in package `com.example.mti.dto` with components: `@JsonProperty("error_code") String errorCode`, `String title`, `String message`.

**Task 3.1.g — Create ErrorResponseDto** — file: `source/src/main/java/com/example/mti/dto/ErrorResponseDto.java`

Create Java record `ErrorResponseDto` in package `com.example.mti.dto` with components: `MetaDto meta` and `ErrorDataDto data`.

**Complexity:** S | **Dependencies:** Story 2.1

---

### Epic 4: Exception Handling

**Goal:** Implement `MtiApiException` and `GlobalExceptionHandler` so that all error paths return consistent `ErrorResponseDto` JSON with the correct HTTP status code.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 4.1: Exception Infrastructure

**As a** developer **I want** a typed exception and a `@RestControllerAdvice` handler **so that** all error paths return consistent structured error responses.

**Background for implementer:** `MtiApiException` carries an `ErrorCode` enum constant (which bundles the HTTP status, code string, and title), the error message, and the `requestId` so the handler can include it in `MetaDto` without re-reading request attributes. `GlobalExceptionHandler` also handles the generic `Exception` fallback to prevent stack traces leaking to clients — it reads `requestId` from `HttpServletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` for errors that bypass the service (e.g., 404 from Spring MVC before the controller is called).

**Acceptance Criteria:**
- [ ] `MtiApiException` extends `RuntimeException`, carries `ErrorCode errorCode`, `String message`, `String requestId`
- [ ] `GlobalExceptionHandler` is annotated `@RestControllerAdvice` and handles `MtiApiException` returning `ResponseEntity<ErrorResponseDto>` with the correct HTTP status
- [ ] Fallback handler catches `Exception` and returns 500 with `ERR_105`
- [ ] All error responses include populated `meta.request_id` and `meta.request_timestamp`

**Tasks:**

**Task 4.1.a — Create MtiApiException** — file: `source/src/main/java/com/example/mti/exception/MtiApiException.java`

Create class `MtiApiException` in package `com.example.mti.exception` extending `RuntimeException`. Add constructor `MtiApiException(ErrorCode errorCode, String message, String requestId)` that calls `super(message)` and stores all three as private final fields. Provide public getters `getErrorCode()` returning `ErrorCode`, `getRequestId()` returning `String`. The `getMessage()` method is inherited from `Throwable`. Import `com.example.mti.constant.ErrorCode`. No annotations on this class.

**Task 4.1.b — Create GlobalExceptionHandler** — file: `source/src/main/java/com/example/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in package `com.example.mti.exception`, annotated `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`.

Add method `handleMtiApiException(MtiApiException ex)` returning `ResponseEntity<ErrorResponseDto>`, annotated `@ExceptionHandler(MtiApiException.class)`. Inside: log at WARN `log.warn("MTI API error requestId={} errorCode={} message={}", ex.getRequestId(), ex.getErrorCode().getCode(), ex.getMessage())`; build `MetaDto meta = new MetaDto(ex.getRequestId(), Instant.now().toString())`; build `ErrorDataDto errorData = new ErrorDataDto(ex.getErrorCode().getCode(), ex.getErrorCode().getTitle(), ex.getMessage())`; return `ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(new ErrorResponseDto(meta, errorData))`.

Add method `handleGenericException(Exception ex, HttpServletRequest request)` returning `ResponseEntity<ErrorResponseDto>`, annotated `@ExceptionHandler(Exception.class)`. Inside: log at ERROR `log.error("Unexpected error on path={}: {}", request.getRequestURI(), ex.getMessage(), ex)`; extract `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`, defaulting to `"unknown"` if null; build `MetaDto`, `ErrorDataDto` (using `ErrorCode.ERR_105`), return `ResponseEntity.status(500).body(new ErrorResponseDto(meta, errorData))`.

**Complexity:** S | **Dependencies:** Stories 2.1, 3.1

---

### Epic 5: Filter Chain & Request Tracing

**Goal:** Implement `RequestIdFilter` that generates a UUID v4 `request_id` per request and propagates it via MDC for log correlation.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 5.1: Request ID Filter

**As a** developer **I want** `RequestIdFilter` to generate and attach a UUID v4 per request **so that** all log statements and error responses share a traceable `request_id`.

**Background for implementer:** `RequestIdFilter` implements `jakarta.servlet.Filter` (Spring Boot 3 uses the Jakarta namespace, not `javax.servlet`). The UUID is stored in both `HttpServletRequest` attributes (for downstream Java code to read via `RequestIdFilter.REQUEST_ID_ATTR`) and in SLF4J MDC (for automatic inclusion in structured log output). MDC must be cleared in a `finally` block to prevent thread-pool leakage. The `@Order(1)` annotation ensures this filter runs first in the chain, before `RateLimitFilter`.

**Acceptance Criteria:**
- [ ] `RequestIdFilter` is `@Component`, `@Order(1)`, implements `jakarta.servlet.Filter`
- [ ] `public static final String REQUEST_ID_ATTR = "requestId"` is declared on the class
- [ ] UUID v4 is generated per request, set as request attribute and MDC key `"requestId"`
- [ ] MDC is cleared in a `finally` block after the filter chain completes

**Tasks:**

**Task 5.1.a — Create RequestIdFilter** — file: `source/src/main/java/com/example/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` in package `com.example.mti.filter` implementing `jakarta.servlet.Filter`, annotated `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"`. Declare `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`. Implement `doFilter(ServletRequest request, ServletResponse response, FilterChain chain)` throwing `IOException, ServletException`. Cast `request` to `HttpServletRequest httpRequest`. Generate `String requestId = UUID.randomUUID().toString()`. Call `httpRequest.setAttribute(REQUEST_ID_ATTR, requestId)`. Call `MDC.put("requestId", requestId)`. Log at DEBUG: `log.debug("Assigned requestId={} to {} {}", requestId, httpRequest.getMethod(), httpRequest.getRequestURI())`. Wrap `chain.doFilter(request, response)` in a try block; in `finally` call `MDC.remove("requestId")`.

**Complexity:** S | **Dependencies:** Story 1.2

---

### Epic 6: Rate Limiting

**Goal:** Implement `RateLimitFilter` using Bucket4j to enforce 100 requests per minute per API key before the request reaches the controller.
**Priority:** Medium | **Estimated Complexity:** M

---

#### Story 6.1: Rate Limiting Filter

**As a** developer **I want** `RateLimitFilter` to enforce per-key rate limits **so that** the API is protected from abuse.

**Background for implementer:** Bucket4j's `Bucket.builder().addLimit(Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1)).build()).build()` creates a token-bucket limiter. Each unique `X-API-Key` header value gets its own `Bucket` stored in a `ConcurrentHashMap`. `computeIfAbsent` ensures exactly one bucket per key even under concurrent access. This is single-node in-process storage — if the service is scaled horizontally, rate limits are per-instance. An `X-API-Key` header is required; absent keys receive an immediate 400. `@Order(2)` places this filter after `RequestIdFilter` so `requestId` is already in MDC for logging. The `requestsPerMinute` value is read from `app.rate-limit.requests-per-minute` injected via `@Value`.

**Acceptance Criteria:**
- [ ] `RateLimitFilter` is `@Order(2)`, reads `app.rate-limit.requests-per-minute` via `@Value`
- [ ] Requests without `X-API-Key` header receive HTTP 400 with `ERR_102` JSON body
- [ ] Requests exceeding the per-key limit receive HTTP 429 with `Retry-After: 60` header and `ERR_102` JSON body
- [ ] Buckets are stored per key in a `ConcurrentHashMap<String, Bucket>`

**Tasks:**

**Task 6.1.a — Create RateLimitFilter** — file: `source/src/main/java/com/example/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` in package `com.example.mti.filter` implementing `jakarta.servlet.Filter`, annotated `@Component` and `@Order(2)`. Inject `@Value("${app.rate-limit.requests-per-minute:100}") long requestsPerMinute` via field injection (or constructor with `@Value` on the parameter). Declare `private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>()`. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`.

Implement `doFilter(ServletRequest request, ServletResponse response, FilterChain chain)` throwing `IOException, ServletException`. Cast to `HttpServletRequest httpRequest` and `HttpServletResponse httpResponse`. Get `String apiKey = httpRequest.getHeader("X-API-Key")`. If `apiKey` is null or blank: log at WARN `log.warn("Missing X-API-Key header requestId={}", MDC.get("requestId"))`; set `httpResponse.setStatus(400)`, `httpResponse.setContentType("application/json")`, write JSON string `{"meta":{"request_id":"` + MDC value for `requestId` + `","request_timestamp":"` + `Instant.now().toString()` + `"},"data":{"error_code":"ERR_102","title":"Invalid Parameters","message":"X-API-Key header is required"}}` to `httpResponse.getWriter()`; return without calling chain.

Otherwise get or create bucket: `Bucket bucket = buckets.computeIfAbsent(apiKey, k -> Bucket.builder().addLimit(Bandwidth.builder().capacity(requestsPerMinute).refillGreedy(requestsPerMinute, Duration.ofMinutes(1)).build()).build())`. Call `boolean consumed = bucket.tryConsume(1)`. If `!consumed`: log at WARN `log.warn("Rate limit exceeded for apiKey={} requestId={}", apiKey, MDC.get("requestId"))`; set `httpResponse.setStatus(429)`, `httpResponse.setHeader("Retry-After", "60")`, `httpResponse.setContentType("application/json")`, write JSON body with `error_code: "ERR_102"`, `title: "Invalid Parameters"`, `message: "Rate limit exceeded. Maximum " + requestsPerMinute + " requests per minute."`; return. Otherwise call `chain.doFilter(request, response)`. Import `io.github.bucket4j.Bucket`, `io.github.bucket4j.Bandwidth`.

**Complexity:** M | **Dependencies:** Story 5.1

---

### Epic 7: Controller Layer

**Goal:** Implement `VesselController` as a thin HTTP adapter that delegates entirely to `MtiScoresService`.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 7.1: Vessel Controller

**As a** developer **I want** `VesselController` to expose `GET /api/v1/vessels/{imo}/mti-scores` **so that** clients can retrieve MTI scores via HTTP.

**Background for implementer:** The controller is intentionally kept thin — it extracts `imo`, `year`, `month` from the HTTP request, retrieves the `requestId` from the attribute set by `RequestIdFilter`, delegates to `MtiScoresService.getScores()`, and wraps the result in a `ResponseEntity`. All validation and business logic live in the service; all error serialisation lives in `GlobalExceptionHandler`. The controller does NOT catch exceptions. `@Validated` at the class level enables Bean Validation for `@RequestParam` constraints if added later.

**Acceptance Criteria:**
- [ ] Class-level `@RequestMapping("/api/v1")`; method-level `@GetMapping("/vessels/{imo}/mti-scores")`
- [ ] `requestId` is retrieved from `request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` cast to `String`
- [ ] Returns `ResponseEntity.ok(response)` on success
- [ ] Does not catch any exceptions — they propagate to `GlobalExceptionHandler`

**Tasks:**

**Task 7.1.a — Create VesselController** — file: `source/src/main/java/com/example/mti/controller/VesselController.java`

Create class `VesselController` in package `com.example.mti.controller`, annotated `@RestController`, `@RequestMapping("/api/v1")`, `@Validated`. Inject `MtiScoresService mtiScoresService` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(VesselController.class)`.

Implement method `getMtiScores(@PathVariable String imo, @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, HttpServletRequest request)` returning `ResponseEntity<SuccessResponseDto>`, annotated `@GetMapping("/vessels/{imo}/mti-scores")`. Inside: retrieve `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if null default to `UUID.randomUUID().toString()`. Log at INFO: `log.info("Received MTI score request requestId={} imo={} year={} month={}", requestId, imo, year, month)`. Call `SuccessResponseDto response = mtiScoresService.getScores(imo, year, month, requestId)`. Log at INFO: `log.info("Returning MTI score response requestId={} imo={}", requestId, imo)`. Return `ResponseEntity.ok(response)`.

**Complexity:** S | **Dependencies:** Stories 4.1 (service), 5.1 (RequestIdFilter constant)

---

### Epic 8: Service Layer & Business Logic

**Goal:** Implement `MtiScoresService` with parameter validation, query routing, and response mapping from `MtiScoreRecord` to `SuccessResponseDto`.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 8.1: MtiScores Service

**As a** developer **I want** `MtiScoresService` to orchestrate validation, query routing, and response mapping **so that** the controller remains thin and all business logic is testable in isolation.

**Background for implementer:** The service is the single place where all five business rule violations are detected and thrown as `MtiApiException`. The validation order matters: IMO format is checked first (ERR_103), then month-without-year (ERR_102), then year range (ERR_104), then month range (ERR_104). Database exceptions from `JdbcTemplate` manifest as `DataAccessException` subclasses — they are caught and rethrown as `MtiApiException(ErrorCode.ERR_105, ...)` so the exception handler returns a 500 rather than an unhandled 500.

**Acceptance Criteria:**
- [ ] Annotated `@Service`; injects `MtiScoresRepository`
- [ ] `getScores(String imo, Integer year, Integer month, String requestId)` returns `SuccessResponseDto` or throws `MtiApiException`
- [ ] Validation order: ERR_103 → ERR_102 → ERR_104 (year) → ERR_104 (month)
- [ ] Correct repository method is called for each parameter combination
- [ ] `DataAccessException` is caught and rethrown as `MtiApiException` with `ErrorCode.ERR_105`
- [ ] Log at INFO on entry and success; WARN on not-found; ERROR on database exception

**Tasks:**

**Task 8.1.a — Create MtiScoresService** — file: `source/src/main/java/com/example/mti/service/MtiScoresService.java`

Create class `MtiScoresService` in package `com.example.mti.service`, annotated `@Service`. Inject `MtiScoresRepository mtiScoresRepository` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class)` and `private static final String IMO_PATTERN = "^[0-9]{7}$"`.

Implement `getScores(String imo, Integer year, Integer month, String requestId)` returning `SuccessResponseDto` (no checked exceptions). Log at INFO on entry: `log.info("Processing MTI score request requestId={} imo={} year={} month={}", requestId, imo, year, month)`.

Perform validation in this exact order: (1) if `imo` is null or does not match `IMO_PATTERN`, throw `new MtiApiException(ErrorCode.ERR_103, "IMO number must be 7 digits", requestId)`; (2) if `month != null && year == null`, throw `new MtiApiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified", requestId)`; (3) if `year != null && (year < 2000 || year > 2100)`, throw `new MtiApiException(ErrorCode.ERR_104, "Invalid year value: " + year, requestId)`; (4) if `month != null && (month < 1 || month > 12)`, throw `new MtiApiException(ErrorCode.ERR_104, "Invalid month value: " + month, requestId)`.

Route to repository inside a try-catch for `org.springframework.dao.DataAccessException`: if `year != null && month != null` call `mtiScoresRepository.findByYearAndMonth(imo, year, month)`; else if `year != null` call `mtiScoresRepository.findByYear(imo, year)`; else call `mtiScoresRepository.findLatest(imo)`. On catch log at ERROR `log.error("Database error for requestId={} imo={}: {}", requestId, imo, ex.getMessage())` then throw `new MtiApiException(ErrorCode.ERR_105, "Database connection or query error", requestId)`.

If `Optional` is empty log at WARN `log.warn("No MTI scores found requestId={} imo={} year={} month={}", requestId, imo, year, month)` then throw `new MtiApiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imo, requestId)`.

Map the record: build `ScoresDto scores = new ScoresDto(record.mtiScore(), record.vesselScore(), record.reportingScore(), record.voyagesScore(), record.emissionsScore(), record.sanctionsScore())`; build `MetadataDto metadata = new MetadataDto(record.createdAt().toString(), record.updatedAt().toString())`; build `MtiScoreDataDto data = new MtiScoreDataDto(record.imoNumber(), record.year(), record.month(), scores, metadata)`; build `MetaDto meta = new MetaDto(requestId, Instant.now().toString())`; build and return `new SuccessResponseDto(meta, data)`. Log at INFO: `log.info("Successfully retrieved MTI scores requestId={} imo={}", requestId, imo)`.

**Complexity:** M | **Dependencies:** Stories 2.2, 3.1, 4.1

---

### Epic 9: Testing

**Goal:** Achieve ≥80% code coverage with unit tests for the service and repository, and integration tests for the full HTTP stack using Testcontainers.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 9.1: Repository Tests with Testcontainers

**As a** developer **I want** `MtiScoresRepositoryTest` against a real PostgreSQL container **so that** all three repository query variants are verified against actual SQL execution.

**Background for implementer:** `@Testcontainers` + `@Container` spins up a `PostgreSQLContainer` (Docker image `postgres:15-alpine`). `@DynamicPropertySource` overrides `spring.datasource.*` to point at the container. Flyway runs automatically at startup, applying V1 (schema) and V2 (seed data), so test assertions can reference the exact values inserted in Task 1.3.b.

**Acceptance Criteria:**
- [ ] `findLatest("9123456")` returns record with year=2024, month=1, mtiScore=85.50
- [ ] `findByYear("9123456", 2023)` returns record with year=2023, month=6 (latest month in 2023)
- [ ] `findByYearAndMonth("9123456", 2023, 6)` returns record with month=6, mtiScore=80.00
- [ ] `findLatest("0000000")` returns `Optional.empty()`
- [ ] `findLatest("9999998")` returns record with mtiScore=null (all scores null)

**Tasks:**

**Task 9.1.a — Create application-test.yml** — file: `source/src/test/resources/application-test.yml`

Create `application-test.yml` with `spring.flyway.enabled: true` and `app.rate-limit.requests-per-minute: 10000`. The datasource URL, username, and password will be overridden at runtime by `@DynamicPropertySource` in each test class — no static values needed here. Set `spring.datasource.url: jdbc:postgresql://localhost:5432/test` as a placeholder that will be overridden.

**Task 9.1.b — Create MtiScoresRepositoryTest** — file: `source/src/test/java/com/example/mti/repository/MtiScoresRepositoryTest.java`

Create class `MtiScoresRepositoryTest` in package `com.example.mti.repository`, annotated `@SpringBootTest`, `@Testcontainers`, `@ActiveProfiles("test")`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Add static method `configureProperties(DynamicPropertyRegistry registry)` annotated `@DynamicPropertySource` that calls `registry.add("spring.datasource.url", postgres::getJdbcUrl)`, `registry.add("spring.datasource.username", postgres::getUsername)`, `registry.add("spring.datasource.password", postgres::getPassword)`. Inject `MtiScoresRepository repository` with `@Autowired`.

Write `@Test findLatest_returnsLatestRecord()`: call `Optional<MtiScoreRecord> result = repository.findLatest("9123456")`; assert `result.isPresent()`; get record; assert `record.year() == 2024`; assert `record.month() == 1`; assert `record.mtiScore() == 85.50`; assert `record.imoNumber().equals("9123456")`.

Write `@Test findByYear_returnsLatestMonthForYear()`: call `repository.findByYear("9123456", 2023)`; assert present; assert `record.year() == 2023`; assert `record.month() == 6` (month 6 is latest in 2023).

Write `@Test findByYearAndMonth_returnsExactRecord()`: call `repository.findByYearAndMonth("9123456", 2023, 6)`; assert present; assert `record.month() == 6`; assert `record.mtiScore() == 80.00`.

Write `@Test findLatest_returnsEmptyForUnknownImo()`: call `repository.findLatest("0000000")`; assert `result.isEmpty()`.

Write `@Test findLatest_returnsNullScoresWhenAllNull()`: call `repository.findLatest("9999998")`; assert present; assert `record.mtiScore() == null`; assert `record.vesselScore() == null`; assert `record.sanctionsScore() == null`.

**Complexity:** M | **Dependencies:** Stories 1.3, 2.2

---

#### Story 9.2: Service Unit Tests

**As a** developer **I want** `MtiScoresServiceTest` with mocked repository **so that** all business logic branches are verified without a database dependency.

**Acceptance Criteria:**
- [ ] All 5 error code scenarios are tested
- [ ] Correct repository method is verified for each parameter combination
- [ ] `SuccessResponseDto` field mapping is asserted for key fields

**Tasks:**

**Task 9.2.a — Create MtiScoresServiceTest** — file: `source/src/test/java/com/example/mti/service/MtiScoresServiceTest.java`

Create class `MtiScoresServiceTest` in package `com.example.mti.service`, annotated `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoresRepository mtiScoresRepository` and `@InjectMocks MtiScoresService service`.

Define a helper `private MtiScoreRecord buildRecord(String imo, int year, int month, Double mtiScore)` that returns `new MtiScoreRecord(1L, imo, year, month, mtiScore, 90.0, 88.0, 82.0, 87.0, 100.0, OffsetDateTime.now(), OffsetDateTime.now())`.

Write `@Test getScores_invalidImo_throwsERR103()`: call `service.getScores("123", null, null, "req-1")` inside `assertThrows(MtiApiException.class, ...)`; assert `ex.getErrorCode() == ErrorCode.ERR_103`.

Write `@Test getScores_monthWithoutYear_throwsERR102()`: call `service.getScores("9123456", null, 6, "req-1")`; assert `ErrorCode.ERR_102`.

Write `@Test getScores_invalidYear_throwsERR104()`: call `service.getScores("9123456", 1999, null, "req-1")`; assert `ErrorCode.ERR_104`.

Write `@Test getScores_invalidMonth_throwsERR104()`: call `service.getScores("9123456", 2024, 13, "req-1")`; assert `ErrorCode.ERR_104`.

Write `@Test getScores_imoNotFound_throwsERR101()`: stub `when(mtiScoresRepository.findLatest("9999999")).thenReturn(Optional.empty())`; call `service.getScores("9999999", null, null, "req-1")`; assert `ErrorCode.ERR_101`.

Write `@Test getScores_noFilters_callsFindLatest()`: stub `findLatest("9123456")` to return `Optional.of(buildRecord("9123456", 2024, 1, 85.50))`; call `service.getScores("9123456", null, null, "req-1")`; verify `mtiScoresRepository.findLatest("9123456")` called once with `Mockito.verify`; assert `result.data().imoNumber().equals("9123456")`; assert `result.data().scores().mtiScore() == 85.50`.

Write `@Test getScores_yearOnly_callsFindByYear()`: stub `findByYear("9123456", 2023)` to return `Optional.of(buildRecord("9123456", 2023, 6, 80.00))`; call `service.getScores("9123456", 2023, null, "req-1")`; verify `mtiScoresRepository.findByYear("9123456", 2023)` called once.

Write `@Test getScores_yearAndMonth_callsFindByYearAndMonth()`: stub `findByYearAndMonth("9123456", 2023, 6)` to return `Optional.of(buildRecord("9123456", 2023, 6, 80.00))`; call `service.getScores("9123456", 2023, 6, "req-1")`; verify `mtiScoresRepository.findByYearAndMonth("9123456", 2023, 6)` called once.

Write `@Test getScores_dataAccessException_throwsERR105()`: stub `findLatest("9123456")` to throw `new DataAccessResourceFailureException("db down")`; call `service.getScores("9123456", null, null, "req-1")`; assert `ErrorCode.ERR_105`.

**Complexity:** M | **Dependencies:** Story 8.1

---

#### Story 9.3: Integration Tests

**As a** developer **I want** `MtiScoresIntegrationTest` using `MockMvc` and Testcontainers **so that** the full HTTP request/response cycle is verified for all PRD acceptance criteria.

**Acceptance Criteria:**
- [ ] AC1: 200, `$.data.imo_number="9123456"`, `$.data.year=2024`, `$.data.scores.mti_score=85.5`
- [ ] AC2: 200, `$.data.year=2023`, `$.data.month=6`
- [ ] AC3: 200, `$.data.month=6`
- [ ] AC4: 404, `$.data.error_code="ERR_101"`
- [ ] AC5: 400, `$.data.error_code="ERR_103"`
- [ ] AC6: 400, `$.data.error_code="ERR_102"`
- [ ] AC7: 400, `$.data.error_code="ERR_104"`
- [ ] AC8: 200, `$.data.scores.mti_score` is null

**Tasks:**

**Task 9.3.a — Create MtiScoresIntegrationTest** — file: `source/src/test/java/com/example/mti/integration/MtiScoresIntegrationTest.java`

Create class `MtiScoresIntegrationTest` in package `com.example.mti.integration`, annotated `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, `@Testcontainers`, `@ActiveProfiles("test")`, `@AutoConfigureMockMvc`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Add `@DynamicPropertySource` as in `MtiScoresRepositoryTest`. Inject `@Autowired MockMvc mockMvc`.

Define private constant `String API_KEY_HEADER = "X-API-Key"` and `String API_KEY_VALUE = "test-key-001"`. Add this header to every `mockMvc.perform()` call using `.header(API_KEY_HEADER, API_KEY_VALUE)`.

Write `@Test ac1_latestScores()`: perform `GET /api/v1/vessels/9123456/mti-scores`; assert status 200; assert `$.data.imo_number` equals `"9123456"`; assert `$.data.year` equals `2024`; assert `$.data.scores.mti_score` equals `85.5`; assert `$.meta.request_id` is not empty.

Write `@Test ac2_yearFilter()`: perform `GET /api/v1/vessels/9123456/mti-scores?year=2023`; assert 200; assert `$.data.year` equals `2023`; assert `$.data.month` equals `6`.

Write `@Test ac3_yearAndMonthFilter()`: perform `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6`; assert 200; assert `$.data.month` equals `6`.

Write `@Test ac4_imoNotFound()`: perform `GET /api/v1/vessels/9999999/mti-scores`; assert status 404; assert `$.data.error_code` equals `"ERR_101"`.

Write `@Test ac5_invalidImoFormat()`: perform `GET /api/v1/vessels/123/mti-scores`; assert status 400; assert `$.data.error_code` equals `"ERR_103"`.

Write `@Test ac6_monthWithoutYear()`: perform `GET /api/v1/vessels/9123456/mti-scores?month=6`; assert status 400; assert `$.data.error_code` equals `"ERR_102"`.

Write `@Test ac7_invalidMonth()`: perform `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13`; assert status 400; assert `$.data.error_code` equals `"ERR_104"`.

Write `@Test ac8_nullScores()`: perform `GET /api/v1/vessels/9999998/mti-scores`; assert status 200; assert `jsonPath("$.data.scores.mti_score").value(IsNull.nullValue())` (import `org.hamcrest.core.IsNull`).

**Complexity:** L | **Dependencies:** Stories 7.1, 8.1, 9.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo       string    Required    7-digit vessel IMO; must match ^[0-9]{7}$

Query Parameters:
  year      integer   Optional    Filter by year; valid range 2000–2100
  month     integer   Optional    Filter by month 1–12; requires year to be present

Request Headers:
  X-API-Key: string   Required    API key for per-client rate limiting

Success Response — 200:
  meta.request_id               string (UUID v4)    Unique request identifier
  meta.request_timestamp        string (ISO 8601)   UTC timestamp
  data.imo_number               string              7-digit IMO
  data.year                     integer             Year of the score record
  data.month                    integer             Month of the score record
  data.scores.mti_score         number | null       Overall MTI score
  data.scores.vessel_score      number | null
  data.scores.reporting_score   number | null
  data.scores.voyages_score     number | null
  data.scores.emissions_score   number | null
  data.scores.sanctions_score   number | null
  data.metadata.created_at      string (ISO 8601)
  data.metadata.updated_at      string (ISO 8601)

Error Response — 4XX / 5XX:
  meta.request_id               string (UUID v4)
  meta.request_timestamp        string (ISO 8601)
  data.error_code               string              ERR_101 through ERR_105
  data.title                    string              Human-readable error title
  data.message                  string              Detailed error message

Error Code Reference:
  ERR_101   404   No scores found for given IMO/year/month combination
  ERR_102   400   Month specified without year; missing X-API-Key; rate limit exceeded
  ERR_103   400   IMO number must be exactly 7 digits
  ERR_104   400   Year out of range 2000–2100 or month out of range 1–12
  ERR_105   500   Database connection or query error
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 200ms for indexed queries; composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` required |
| Logging | SLF4J with MDC field `requestId`; DEBUG for repository queries; INFO for service entry/exit; WARN for not-found and rate-limit events; ERROR for DB failures |
| Metrics | Spring Boot Actuator at `/actuator/health` and `/actuator/info` |
| Security | Regex IMO validation; year/month range validation; parameterised SQL queries only; no stack traces in error responses |
| Rate Limiting | Token-bucket (Bucket4j); 100 req/min per `X-API-Key`; 429 response with `Retry-After: 60`; in-process for v1 |
| Testing | ≥80% line coverage; unit tests with Mockito; integration tests with Testcontainers PostgreSQL 15 |
| Health / Docs | `/actuator/health` returns `{"status":"UP"}`; Swagger UI at `/swagger-ui.html`; OpenAPI JSON at `/api-docs` |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `VesselController` | `MtiScoresService`, `RequestIdFilter.REQUEST_ID_ATTR` | Delegates all logic; reads requestId from filter attribute |
| `MtiScoresService` | `MtiScoresRepository`, `ErrorCode`, `MtiApiException`, all DTO classes | Orchestrates validation, routing, and response mapping |
| `MtiScoresRepository` | `JdbcTemplate`, `MtiScoreRecord` | Executes parameterised SQL; maps `ResultSet` to domain record |
| `GlobalExceptionHandler` | `MtiApiException`, `ErrorCode`, `RequestIdFilter.REQUEST_ID_ATTR`, `ErrorResponseDto`, `ErrorDataDto`, `MetaDto` | Converts thrown exceptions to structured error responses |
| `RateLimitFilter` | `Bucket4j Bucket`, `Bandwidth`, `app.rate-limit.requests-per-minute` | Per-key token bucket; reads MDC requestId for log correlation |
| `RequestIdFilter` | `MDC`, `UUID` | Injects requestId before all downstream request handling |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Maven POM is the compilation foundation; nothing compiles without it
2. **Story 1.2** — Application entry point and configuration needed before the app can start
3. **Story 1.3** — Database schema must exist before repository tests can run; seed data needed for integration tests
4. **Story 1.4** — Docker setup enables local end-to-end verification early in the cycle
5. **Story 2.1** — Domain model (`MtiScoreRecord`) and `ErrorCode` enum are dependencies for all subsequent layers
6. **Story 2.2** — Repository depends on `MtiScoreRecord`; needed by the service
7. **Story 3.1** — DTOs needed by service for response construction
8. **Story 4.1** — Exception infrastructure must exist before the service throws `MtiApiException`
9. **Story 5.1** — `RequestIdFilter` and its `REQUEST_ID_ATTR` constant needed by controller and exception handler
10. **Story 8.1** — Service depends on repository, DTOs, and exception classes
11. **Story 6.1** — Rate limiting filter depends on `RequestIdFilter` (must run after it)
12. **Story 7.1** — Controller is the last piece of the request chain
13. **Story 9.1** — Repository tests verify data layer with real DB
14. **Story 9.2** — Service unit tests cover all business logic branches
15. **Story 9.3** — Integration tests verify end-to-end flow after all components exist

> Stories 3.1 and 2.2 can be parallelised after Story 2.1. Stories 1.3 and 1.4 can be developed in parallel after Story 1.2. Stories 9.1, 9.2, and 9.3 can be written in parallel once their respective implementation targets are complete.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `MetaDto` | `requestId: String`, `requestTimestamp: String` | `request_id`, `request_timestamp` | Shared by success and error responses |
| `ScoresDto` | `mtiScore: Double`, `vesselScore: Double`, `reportingScore: Double`, `voyagesScore: Double`, `emissionsScore: Double`, `sanctionsScore: Double` | `mti_score`, `vessel_score`, `reporting_score`, `voyages_score`, `emissions_score`, `sanctions_score` | All fields boxed `Double`; nullable |
| `MetadataDto` | `createdAt: String`, `updatedAt: String` | `created_at`, `updated_at` | ISO 8601 strings from `OffsetDateTime.toString()` |
| `MtiScoreDataDto` | `imoNumber: String`, `year: Integer`, `month: Integer`, `scores: ScoresDto`, `metadata: MetadataDto` | `imo_number`, `year`, `month`, `scores`, `metadata` | The `data` field of success response |
| `SuccessResponseDto` | `meta: MetaDto`, `data: MtiScoreDataDto` | `meta`, `data` | Top-level success response body |
| `ErrorDataDto` | `errorCode: String`, `title: String`, `message: String` | `error_code`, `title`, `message` | The `data` field of error response |
| `ErrorResponseDto` | `meta: MetaDto`, `data: ErrorDataDto` | `meta`, `data` | Top-level error response body |
| `MtiScoreRecord` | `id: Long`, `imoNumber: String`, `year: Integer`, `month: Integer`, six `Double` scores, `createdAt: OffsetDateTime`, `updatedAt: OffsetDateTime` | N/A (internal) | Java record; not serialised directly |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_HOST` | Yes | `localhost` | PostgreSQL hostname |
| `DATABASE_PORT` | No (default: `5432`) | `5432` | PostgreSQL port |
| `DATABASE_NAME` | Yes | `mti_db` | Database name |
| `DATABASE_USER` | Yes | `mti_user` | Database username |
| `DATABASE_PASSWORD` | Yes | `mti_pass` | Database password |
| `RATE_LIMIT_RPM` | No (default: `100`) | `100` | Max requests per minute per API key |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | |
| `year` | INTEGER | No | |
| `month` | INTEGER | No | |
| `mti_score` | NUMERIC(5,2) | Yes | |
| `vessel_score` | NUMERIC(5,2) | Yes | |
| `reporting_score` | NUMERIC(5,2) | Yes | |
| `voyages_score` | NUMERIC(5,2) | Yes | |
| `emissions_score` | NUMERIC(5,2) | Yes | |
| `sanctions_score` | NUMERIC(5,2) | Yes | |
| `created_at` | TIMESTAMPTZ | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMPTZ | No | DEFAULT NOW() |

Additional constraints:
- Unique constraint `uq_mti_scores_imo_year_month` on `(imo_number, year, month)`
- Index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)`

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| In-memory rate limiting lost on node restart | M | L | Acceptable for v1; document limitation; plan Redis Bucket4j integration for v2 multi-node deployment |
| NULL score fields cause `0.0` serialisation bug | L | H | Use `rs.getObject("col", Double.class)` not `rs.getDouble("col")` in RowMapper; covered by AC8 integration test |
| Missing composite index causes full-table scan | L | H | Flyway V1 migration creates the index atomically at startup |
| Testcontainers unavailable in CI environment | M | M | Ensure Docker daemon is available in CI runner; annotate tests with `@EnabledIfSystemProperty` guard if needed |
| Month-without-year edge case not validated | L | H | Validated in service layer as second check; covered by AC6 integration test |
| Jackson serialises `null` Double as `0.0` | L | H | Boxed `Double` serialises as JSON `null` in Jackson by default; verify with AC8 test |

---

## DEFINITION OF DONE

### For Each Story
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing (target: ≥80% line coverage)
- [ ] Integration tests passing
- [ ] No new linting errors
- [ ] Acceptance criteria verified

### For the Release
- [ ] All stories complete
- [ ] Performance targets verified under load (p95 < 200ms with index)
- [ ] Security review passed (no SQL injection, no stack traces in responses)
- [ ] API documentation updated (Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/api-docs`)
- [ ] Docker image builds and runs locally with `docker-compose up`
- [ ] All environment variables documented

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven POM is the compilation foundation
2. **Story 1.2** — Application entry point and configuration
3. **Story 1.3 + 1.4** — Database schema and Docker (can be developed in parallel)
4. **Story 2.1** — Domain model and error codes (shared by all layers)
5. **Story 2.2 + 3.1** — Repository and DTOs (can be developed in parallel)
6. **Story 4.1** — Exception classes (depends on ErrorCode from 2.1)
7. **Story 8.1** — Service (depends on 2.2, 3.1, 4.1)
8. **Story 5.1** — RequestIdFilter
9. **Story 6.1** — RateLimitFilter (depends on 5.1)
10. **Story 7.1** — Controller (depends on 8.1 and 5.1)
11. **Story 9.1** — Repository tests (Testcontainers)
12. **Story 9.2** — Service unit tests (Mockito)
13. **Story 9.3** — Integration tests (full stack)

> Stories 9.1, 9.2, and 9.3 can be written in parallel once their respective targets exist. Stories 5.1 and 6.1 can be implemented in parallel with 8.1.
