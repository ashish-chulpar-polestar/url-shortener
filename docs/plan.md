# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The MTI Scores API provides a single REST endpoint `GET /api/v1/vessels/{imo}/mti-scores` to retrieve Maritime Transportation Indicator scores for vessels identified by their IMO number. The API supports optional filtering by year and/or month, implements business logic to resolve the "latest" score when no filters are provided, and enforces strict input validation with five domain-specific error codes. The implementation uses Spring Boot 3.2 on Java 17 with PostgreSQL as the data store, Flyway for schema management, and Bucket4j for in-process rate limiting. All responses follow a consistent envelope pattern with `meta` (request ID, timestamp) and `data` sections.

---

## TECHNICAL ANALYSIS

### Recommended Stack

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 17 | LTS release, widely adopted, strong Spring Boot support |
| Build | Maven 3.9 | Standard for Spring Boot projects, rich plugin ecosystem |
| Framework | Spring Boot 3.2 | Convention-over-configuration, embedded Tomcat, built-in actuator |
| Database | PostgreSQL 15 | ACID-compliant, excellent numeric type support for score decimals |
| DB Access | Spring JdbcTemplate | Lightweight, direct SQL control, no ORM overhead for single-table reads |
| Migration | Flyway | Versioned SQL migrations, integrates natively with Spring Boot |
| Rate Limiting | Bucket4j 8.x (in-process) | Token-bucket algorithm, no Redis dependency for MVP |
| Validation | Jakarta Bean Validation (Hibernate Validator) | Native Spring Boot support, declarative constraints |
| Testing | JUnit 5, Mockito 5, Testcontainers | Standard stack; Testcontainers enables real PostgreSQL in integration tests |
| Container | Docker + docker-compose | Reproducible local dev environment with PostgreSQL |

### Project Structure

```
mti-scores-api/
└── source/
    ├── src/
    │   ├── main/
    │   │   ├── java/com/example/mtiscores/
    │   │   │   ├── MtiScoresApplication.java
    │   │   │   ├── config/
    │   │   │   │   └── RateLimitConfig.java
    │   │   │   ├── constant/
    │   │   │   │   └── ErrorCode.java
    │   │   │   ├── controller/
    │   │   │   │   └── MtiScoresController.java
    │   │   │   ├── dto/
    │   │   │   │   ├── ErrorDataDto.java
    │   │   │   │   ├── ErrorResponseDto.java
    │   │   │   │   ├── MetaDto.java
    │   │   │   │   ├── MetadataDto.java
    │   │   │   │   ├── ScoresDto.java
    │   │   │   │   ├── SuccessResponseDto.java
    │   │   │   │   └── VesselDataDto.java
    │   │   │   ├── exception/
    │   │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   │   └── MtiScoresException.java
    │   │   │   ├── filter/
    │   │   │   │   ├── RateLimitFilter.java
    │   │   │   │   └── RequestIdFilter.java
    │   │   │   ├── model/
    │   │   │   │   └── MtiScoreRecord.java
    │   │   │   ├── repository/
    │   │   │   │   └── MtiScoresRepository.java
    │   │   │   └── service/
    │   │   │       └── MtiScoresService.java
    │   │   └── resources/
    │   │       ├── application.yml
    │   │       └── db/migration/
    │   │           └── V1__create_mti_scores_history.sql
    │   └── test/
    │       ├── java/com/example/mtiscores/
    │       │   ├── controller/
    │       │   │   └── MtiScoresControllerTest.java
    │       │   ├── service/
    │       │   │   └── MtiScoresServiceTest.java
    │       │   └── integration/
    │       │       └── MtiScoresIntegrationTest.java
    │       └── resources/
    │           ├── application-test.yml
    │           └── db/migration/
    │               └── V2__seed_test_data.sql
    ├── Dockerfile
    ├── docker-compose.yml
    └── pom.xml
```

### Integration Points

The API reads from a single PostgreSQL table `mti_scores_history`. No external HTTP calls, message queues, or cloud services are required. The database connection is the only external dependency at runtime.

### Technical Constraints

- Rate limit: 100 requests per minute per client IP (PRD specifies "per API key" but no API key auth is defined; IP-based rate limiting is the pragmatic MVP choice — see ADR-002)
- IMO validation: regex `^[0-9]{7}$` enforced at controller level before any DB access
- All numeric score columns are nullable; null DB values must serialize as JSON `null`
- Request IDs must be UUID v4; all log lines must include `requestId` in MDC
- Year range: 2000–2100; month range: 1–12

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.mtiscores` | **Group ID:** `com.example` | **Artifact ID:** `mti-scores-api`

### Overview

The backend implements a single REST endpoint `GET /api/v1/vessels/{imo}/mti-scores` that reads from a PostgreSQL table, applies business-logic filtering, and returns a consistently enveloped response. The work spans project scaffolding, database schema, domain and DTO layers, repository, service, controller, exception handling, cross-cutting filters, and full test coverage.

---

### Epic 1: Project Bootstrap

**Goal:** Establish a runnable Spring Boot project with correct Maven dependencies, configuration, and database schema.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Maven Project Setup

**As a** developer **I want** a correctly configured Maven project **so that** all subsequent code can be compiled, tested, and packaged.

**Acceptance Criteria:**
- [ ] `source/pom.xml` compiles with `mvn clean package -DskipTests`
- [ ] Spring Boot application starts with `java -jar`
- [ ] Actuator `/actuator/health` returns `{"status":"UP"}`
- [ ] All required dependency artifact IDs are present

**Tasks:**

**Task 1.1.a — Create `pom.xml`** — file: `source/pom.xml`

Create the Maven POM with parent `org.springframework.boot:spring-boot-starter-parent:3.2.5`, group `com.example`, artifact `mti-scores-api`, version `1.0.0-SNAPSHOT`, Java source/target `17`. Add dependencies: `org.springframework.boot:spring-boot-starter-web` (web MVC and embedded Tomcat), `org.springframework.boot:spring-boot-starter-jdbc` (JdbcTemplate), `org.springframework.boot:spring-boot-starter-validation` (Bean Validation), `org.springframework.boot:spring-boot-starter-actuator` (health endpoint), `org.flywaydb:flyway-core` (schema migrations), `org.postgresql:postgresql` (JDBC driver, runtime scope), `com.github.ben-manes.caffeine:caffeine:3.1.8` (in-memory cache for rate limit buckets), `com.bucket4j:bucket4j-core:8.10.1` (token-bucket rate limiting), `com.fasterxml.jackson.core:jackson-databind` (JSON — pulled transitively but declare version alignment). Test dependencies: `org.springframework.boot:spring-boot-starter-test` (JUnit 5 + Mockito), `org.testcontainers:testcontainers:1.19.8`, `org.testcontainers:postgresql:1.19.8`, `org.testcontainers:junit-jupiter:1.19.8`. Add the `spring-boot-maven-plugin` for fat-jar packaging.

**Task 1.1.b — Create `MtiScoresApplication.java`** — file: `source/src/main/java/com/example/mtiscores/MtiScoresApplication.java`

Create the Spring Boot entry point class `MtiScoresApplication` annotated with `@SpringBootApplication`. The class contains a `main` method that calls `SpringApplication.run(MtiScoresApplication.class, args)`. No additional configuration is needed here.

**Task 1.1.c — Create `application.yml`** — file: `source/src/main/resources/application.yml`

Create the main application configuration. Set `spring.application.name` to `mti-scores-api`. Configure `spring.datasource.url` to read from environment variable `${DATABASE_URL}`, `spring.datasource.username` to `${DATABASE_USERNAME}`, `spring.datasource.password` to `${DATABASE_PASSWORD}`. Set `spring.flyway.enabled=true` and `spring.flyway.locations=classpath:db/migration`. Set `management.endpoints.web.exposure.include=health`. Set `mti.rate-limit.requests-per-minute=100` as the configurable rate limit threshold. Set `server.port=8080`.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Database Schema Migration

**As a** developer **I want** a Flyway migration that creates the `mti_scores_history` table **so that** all subsequent data access code has a schema to work against.

**Background for implementer:** Flyway discovers migration scripts on the classpath at `classpath:db/migration` by convention. Files must be named `V{version}__{description}.sql`. The table name `mti_scores_history` comes directly from the PRD's SQL queries. The composite index `(imo_number, year DESC, month DESC)` is required by the PRD for query performance.

**Acceptance Criteria:**
- [ ] Running `mvn flyway:migrate` applies `V1__create_mti_scores_history.sql` without errors
- [ ] Table `mti_scores_history` exists with all 12 columns
- [ ] Index `idx_mti_scores_imo_year_month` is created
- [ ] All six score columns are nullable

**Tasks:**

**Task 1.2.a — Create Flyway migration `V1__create_mti_scores_history.sql`** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Write the CREATE TABLE statement for `mti_scores_history` with columns: `id` as BIGSERIAL PRIMARY KEY, `imo_number` as VARCHAR(7) NOT NULL, `year` as INTEGER NOT NULL, `month` as INTEGER NOT NULL, `mti_score` as NUMERIC(6,2) NULL, `vessel_score` as NUMERIC(6,2) NULL, `reporting_score` as NUMERIC(6,2) NULL, `voyages_score` as NUMERIC(6,2) NULL, `emissions_score` as NUMERIC(6,2) NULL, `sanctions_score` as NUMERIC(6,2) NULL, `created_at` as TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), `updated_at` as TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(). After the table creation, add a CREATE INDEX statement naming the index `idx_mti_scores_imo_year_month` on table `mti_scores_history` for columns `(imo_number, year DESC, month DESC)`. Also add a UNIQUE constraint named `uq_mti_scores_imo_year_month` on `(imo_number, year, month)` to prevent duplicate records.

**Task 1.2.b — Create test seed migration `V2__seed_test_data.sql`** — file: `source/src/test/resources/db/migration/V2__seed_test_data.sql`

**Background for implementer:** Placing seed data in `src/test/resources/db/migration` makes it available only during tests. Configure `spring.flyway.locations` in `application-test.yml` to include both `classpath:db/migration` and `classpath:/db/migration` (test classpath). Insert the following rows into `mti_scores_history` (all timestamps as `'2024-01-01T00:00:00Z'`): Row 1 — imo_number=`'9123456'`, year=`2024`, month=`1`, mti_score=`85.50`, vessel_score=`90.00`, reporting_score=`88.75`, voyages_score=`82.30`, emissions_score=`87.60`, sanctions_score=`100.00`. Row 2 — imo_number=`'9123456'`, year=`2023`, month=`12`, mti_score=`80.00`, vessel_score=`85.00`, reporting_score=`83.50`, voyages_score=`78.00`, emissions_score=`82.00`, sanctions_score=`95.00`. Row 3 — imo_number=`'9123456'`, year=`2023`, month=`6`, mti_score=`75.00`, vessel_score=`80.00`, reporting_score=`78.00`, voyages_score=`72.00`, emissions_score=`76.00`, sanctions_score=`90.00`. Row 4 — imo_number=`'8000001'`, year=`2024`, month=`3`, all six score columns as NULL.

**Task 1.2.c — Create `application-test.yml`** — file: `source/src/test/resources/application-test.yml`

Create the test profile configuration. Set `spring.flyway.locations` to `classpath:db/migration,classpath:/db/migration`. Set `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` to empty strings — Testcontainers will override these dynamically in integration tests using `@DynamicPropertySource`. Disable rate limiting in tests by setting `mti.rate-limit.requests-per-minute=10000`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Domain Model, DTOs, and Constants

**Goal:** Define all data structures — domain model, response envelopes, and error code constants — that are shared across repository, service, and controller layers.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 2.1: Error Code Constants

**As a** developer **I want** a single `ErrorCode` constant class **so that** all layers use the same string codes without magic literals.

**Acceptance Criteria:**
- [ ] `ErrorCode` class contains all five error code strings
- [ ] No other class in the project declares error code strings inline

**Tasks:**

**Task 2.1.a — Create `ErrorCode.java`** — file: `source/src/main/java/com/example/mtiscores/constant/ErrorCode.java`

Create the class `ErrorCode` as a final utility class with a private constructor to prevent instantiation. Declare the following public static final String constants: `ERR_101` with value `"ERR_101"` (Resource Not Found), `ERR_102` with value `"ERR_102"` (Invalid Parameters — month without year), `ERR_103` with value `"ERR_103"` (Invalid IMO Format), `ERR_104` with value `"ERR_104"` (Invalid Date Range), `ERR_105` with value `"ERR_105"` (Internal Server Error). These match the PRD's error code table exactly.

**Complexity:** S | **Dependencies:** None

---

#### Story 2.2: Domain Model and DTO Classes

**As a** developer **I want** a domain model and DTO classes **so that** the repository can return typed records and the controller can serialize structured JSON.

**Background for implementer:** `MtiScoreRecord` is the internal domain object mapped from a ResultSet. The DTO classes form the JSON response envelope. Using separate classes instead of maps ensures type safety and makes Jackson serialization predictable. Annotating `ScoresDto` with `@JsonInclude(JsonInclude.Include.ALWAYS)` preserves null score values (AC8 requires null fields to appear as `null` in JSON, not be omitted).

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` has all 11 data fields including both timestamps
- [ ] `ScoresDto` uses `@JsonInclude(JsonInclude.Include.ALWAYS)` to preserve null values
- [ ] `SuccessResponseDto` serializes to the exact JSON structure in the PRD
- [ ] `ErrorResponseDto` serializes to the exact error JSON structure in the PRD

**Tasks:**

**Task 2.2.a — Create `MtiScoreRecord.java`** — file: `source/src/main/java/com/example/mtiscores/model/MtiScoreRecord.java`

Create the class `MtiScoreRecord` as a Java record with the following components in order: `String imoNumber`, `int year`, `int month`, `Double mtiScore` (nullable), `Double vesselScore` (nullable), `Double reportingScore` (nullable), `Double voyagesScore` (nullable), `Double emissionsScore` (nullable), `Double sanctionsScore` (nullable), `java.time.OffsetDateTime createdAt`, `java.time.OffsetDateTime updatedAt`. Use boxed `Double` for all six score fields to allow null values.

**Task 2.2.b — Create `MetaDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/MetaDto.java`

Create the class `MetaDto` as a Java record with components `String requestId` and `String requestTimestamp`. Apply `@JsonProperty("request_id")` to `requestId` and `@JsonProperty("request_timestamp")` to `requestTimestamp`. The `requestId` will hold a UUID v4 string; `requestTimestamp` will hold an ISO 8601 UTC string.

**Task 2.2.c — Create `ScoresDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/ScoresDto.java`

Create the class `ScoresDto` as a Java record with components `Double mtiScore`, `Double vesselScore`, `Double reportingScore`, `Double voyagesScore`, `Double emissionsScore`, `Double sanctionsScore` — all boxed `Double` to allow null. Annotate the class with `@JsonInclude(JsonInclude.Include.ALWAYS)` to ensure null fields serialize as `null` in JSON (required by AC8). Apply `@JsonProperty` on each component: `"mti_score"`, `"vessel_score"`, `"reporting_score"`, `"voyages_score"`, `"emissions_score"`, `"sanctions_score"`.

**Task 2.2.d — Create `MetadataDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/MetadataDto.java`

Create the class `MetadataDto` as a Java record with components `String createdAt` and `String updatedAt`. Annotate each with `@JsonProperty("created_at")` and `@JsonProperty("updated_at")` respectively. The service will format the `OffsetDateTime` values from `MtiScoreRecord` as ISO 8601 strings before constructing this DTO.

**Task 2.2.e — Create `VesselDataDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/VesselDataDto.java`

Create the class `VesselDataDto` as a Java record with components `String imoNumber`, `Integer year`, `Integer month`, `ScoresDto scores`, `MetadataDto metadata`. Annotate `imoNumber` with `@JsonProperty("imo_number")`. The `year` and `month` fields serialize as-is using their natural names.

**Task 2.2.f — Create `SuccessResponseDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/SuccessResponseDto.java`

Create the class `SuccessResponseDto` as a Java record with components `MetaDto meta` and `VesselDataDto data`. This is the top-level envelope for 200 OK responses and matches the JSON shape in the PRD's success response example.

**Task 2.2.g — Create `ErrorDataDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/ErrorDataDto.java`

Create the class `ErrorDataDto` as a Java record with components `String errorCode`, `String title`, `String message`. Annotate `errorCode` with `@JsonProperty("error_code")`.

**Task 2.2.h — Create `ErrorResponseDto.java`** — file: `source/src/main/java/com/example/mtiscores/dto/ErrorResponseDto.java`

Create the class `ErrorResponseDto` as a Java record with components `MetaDto meta` and `ErrorDataDto data`. This is the top-level envelope for all error responses (4xx and 5xx).

**Complexity:** S | **Dependencies:** Story 2.1

---

### Epic 3: Data Access Layer

**Goal:** Implement the repository that executes the three SQL query patterns from the PRD.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: MTI Scores Repository

**As a** developer **I want** a repository that executes parameterized SQL queries **so that** the service can retrieve `MtiScoreRecord` objects without writing SQL directly.

**Background for implementer:** `JdbcTemplate` is used instead of JPA because the read-only nature of this API makes the overhead of an entity manager unnecessary. Using `queryForObject` with a `RowMapper` provides explicit control over column mapping. The `RowMapper` maps each ResultSet column by exact name, not by position, to avoid fragile ordering issues. Nullable columns are mapped using `rs.getObject(columnName, Double.class)` so that SQL NULL becomes Java `null` rather than `0.0`.

**Acceptance Criteria:**
- [ ] `findLatest(imoNumber)` uses `ORDER BY year DESC, month DESC LIMIT 1` with `imo_number = ?`
- [ ] `findByYear(imoNumber, year)` uses `AND year = ? ORDER BY month DESC LIMIT 1`
- [ ] `findByYearAndMonth(imoNumber, year, month)` uses `AND year = ? AND month = ? LIMIT 1`
- [ ] Returns `Optional.empty()` when no row is found (handles `EmptyResultDataAccessException`)

**Tasks:**

**Task 3.1.a — Create `MtiScoresRepository.java`** — file: `source/src/main/java/com/example/mtiscores/repository/MtiScoresRepository.java`

Create the class `MtiScoresRepository` annotated with `@Repository`. Inject `JdbcTemplate` via constructor injection (`private final JdbcTemplate jdbcTemplate`). Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresRepository.class)`. Declare a private static final `RowMapper<MtiScoreRecord>` named `MTI_SCORE_ROW_MAPPER` that maps columns: `rs.getString("imo_number")` to `imoNumber`, `rs.getInt("year")` to `year`, `rs.getInt("month")` to `month`, `rs.getObject("mti_score", Double.class)` to `mtiScore`, `rs.getObject("vessel_score", Double.class)` to `vesselScore`, `rs.getObject("reporting_score", Double.class)` to `reportingScore`, `rs.getObject("voyages_score", Double.class)` to `voyagesScore`, `rs.getObject("emissions_score", Double.class)` to `emissionsScore`, `rs.getObject("sanctions_score", Double.class)` to `sanctionsScore`, `rs.getObject("created_at", OffsetDateTime.class)` to `createdAt`, `rs.getObject("updated_at", OffsetDateTime.class)` to `updatedAt`. Implement three public methods: `findLatest(String imoNumber)` returning `Optional<MtiScoreRecord>`, `findByYear(String imoNumber, int year)` returning `Optional<MtiScoreRecord>`, `findByYearAndMonth(String imoNumber, int year, int month)` returning `Optional<MtiScoreRecord>`. Each method logs at DEBUG before executing: `log.debug("Querying mti_scores_history imo={} year={} month={}", imoNumber, year, month)` (use applicable parameters for each variant; for `findLatest` log `log.debug("Querying latest mti_scores_history imo={}", imoNumber)`). Each method calls `jdbcTemplate.queryForObject(sql, MTI_SCORE_ROW_MAPPER, params)` wrapped in a try-catch for `org.springframework.dao.EmptyResultDataAccessException`, returning `Optional.empty()` on catch and `Optional.of(result)` on success.

**Complexity:** M | **Dependencies:** Story 1.2, Story 2.2

---

### Epic 4: Service Layer

**Goal:** Implement business logic that selects the correct repository query method based on input parameters.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 4.1: MTI Scores Service

**As a** developer **I want** a service that applies the filtering rules from the PRD **so that** the controller remains thin and business logic is testable in isolation.

**Background for implementer:** The service handles the four routing cases from the PRD: (1) no year/month → `findLatest`, (2) year only → `findByYear`, (3) year + month → `findByYearAndMonth`, (4) month without year → this is validated at the controller before the service is called, but the service guards defensively against it. The service converts `MtiScoreRecord` to `SuccessResponseDto`, building `MetaDto` from the `requestId` and current UTC timestamp.

**Acceptance Criteria:**
- [ ] No year/month returns the latest record
- [ ] Year only returns the latest record for that year
- [ ] Year + month returns the record for that exact month
- [ ] Repository returning empty throws `MtiScoresException` with code `ErrorCode.ERR_101` and HTTP status 404
- [ ] `SuccessResponseDto` carries the correct `request_id` passed into the method

**Tasks:**

**Task 4.1.a — Create `MtiScoresException.java`** — file: `source/src/main/java/com/example/mtiscores/exception/MtiScoresException.java`

Create the class `MtiScoresException` extending `RuntimeException`. It has three private final fields: `String errorCode`, `String title`, `int httpStatus`. Provide a constructor `MtiScoresException(String errorCode, String title, String message, int httpStatus)` that calls `super(message)` and assigns all three fields. Provide getters `getErrorCode()` returning `String`, `getTitle()` returning `String`, `getHttpStatus()` returning `int`.

**Task 4.1.b — Create `MtiScoresService.java`** — file: `source/src/main/java/com/example/mtiscores/service/MtiScoresService.java`

Create the class `MtiScoresService` annotated with `@Service`. Inject `MtiScoresRepository` via constructor injection. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class)`. Implement the method `getScores(String imoNumber, Integer year, Integer month, String requestId)` returning `SuccessResponseDto`. Inside the method: log at INFO `log.info("Fetching MTI scores imo={} year={} month={} requestId={}", imoNumber, year, month, requestId)`. Select the query method: if both `year` and `month` are null, call `repository.findLatest(imoNumber)`; else if `month` is null (year is present), call `repository.findByYear(imoNumber, year)`; else call `repository.findByYearAndMonth(imoNumber, year, month)`. If the `Optional` is empty, log at WARN `log.warn("No MTI scores found imo={} year={} month={} requestId={}", imoNumber, year, month, requestId)` and throw `new MtiScoresException(ErrorCode.ERR_101, "Resource Not Found", "No MTI scores found for IMO " + imoNumber, 404)`. Map the `MtiScoreRecord` to `SuccessResponseDto`: construct `MetaDto` using `requestId` and `java.time.Instant.now().toString()` for `requestTimestamp`; construct `ScoresDto` from the six nullable score fields; construct `MetadataDto` from `record.createdAt().toString()` and `record.updatedAt().toString()`; construct `VesselDataDto(record.imoNumber(), record.year(), record.month(), scoresDto, metadataDto)`; return `new SuccessResponseDto(metaDto, vesselDataDto)`. Log at DEBUG on success: `log.debug("MTI scores retrieved imo={} year={} month={} requestId={}", imoNumber, year, month, requestId)`.

**Complexity:** M | **Dependencies:** Story 3.1, Story 2.2, Story 4.1.a

---

### Epic 5: REST Controller and Exception Handling

**Goal:** Expose the HTTP endpoint, validate inputs, and translate exceptions to structured error responses.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 5.1: REST Controller

**As a** developer **I want** a Spring MVC controller at `GET /api/v1/vessels/{imo}/mti-scores` **so that** HTTP clients can retrieve MTI scores.

**Acceptance Criteria:**
- [ ] Endpoint matches `GET /api/v1/vessels/{imo}/mti-scores`
- [ ] Returns `200 OK` with `SuccessResponseDto` on valid requests
- [ ] Returns `400` with `ErrorCode.ERR_103` when IMO is not 7 digits
- [ ] Returns `400` with `ErrorCode.ERR_102` when month is provided without year
- [ ] Returns `400` with `ErrorCode.ERR_104` when year is outside 2000–2100 or month is outside 1–12

**Tasks:**

**Task 5.1.a — Create `MtiScoresController.java`** — file: `source/src/main/java/com/example/mtiscores/controller/MtiScoresController.java`

Create the class `MtiScoresController` annotated with `@RestController` and `@RequestMapping("/api/v1/vessels")`. Inject `MtiScoresService` via constructor injection. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresController.class)`. Implement the method `getMtiScores` returning `ResponseEntity<SuccessResponseDto>` annotated with `@GetMapping("/{imo}/mti-scores")`. Method parameters: `@PathVariable String imo`, `@RequestParam(required = false) Integer year`, `@RequestParam(required = false) Integer month`. Inside the method, read `String requestId = MDC.get("requestId")`. Log at INFO: `log.info("GET mti-scores imo={} year={} month={} requestId={}", imo, year, month, requestId)`. Perform validation in order: (1) if `imo` does not match `^[0-9]{7}$`, log at WARN `log.warn("Invalid IMO format imo={} requestId={}", imo, requestId)` and throw `new MtiScoresException(ErrorCode.ERR_103, "Invalid IMO Format", "IMO number must be 7 digits", 400)`; (2) if `month != null && year == null`, throw `new MtiScoresException(ErrorCode.ERR_102, "Invalid Parameters", "Month parameter requires year parameter to be specified", 400)`; (3) if `year != null && (year < 2000 || year > 2100)`, throw `new MtiScoresException(ErrorCode.ERR_104, "Invalid Date Range", "Year must be between 2000 and 2100", 400)`; (4) if `month != null && (month < 1 || month > 12)`, throw `new MtiScoresException(ErrorCode.ERR_104, "Invalid Date Range", "Month must be between 1 and 12", 400)`. Call `service.getScores(imo, year, month, requestId)` and return `ResponseEntity.ok(result)`.

**Complexity:** M | **Dependencies:** Story 4.1, Story 2.1

---

#### Story 5.2: Global Exception Handler

**As a** developer **I want** a `@RestControllerAdvice` that catches `MtiScoresException` and other exceptions **so that** all error responses follow the PRD's error envelope format.

**Background for implementer:** `@RestControllerAdvice` with `@ExceptionHandler` intercepts exceptions thrown from any controller. Returning `ResponseEntity<ErrorResponseDto>` with the correct HTTP status ensures the envelope structure is consistent. Unexpected exceptions (not `MtiScoresException`) must map to `ERR_105` with HTTP 500 to avoid leaking stack traces.

**Acceptance Criteria:**
- [ ] `MtiScoresException` maps to correct HTTP status and error code from exception fields
- [ ] Generic `Exception` maps to `ERR_105` with HTTP 500 and no stack trace in response body
- [ ] All error responses include valid `request_id` from MDC and `request_timestamp`

**Tasks:**

**Task 5.2.a — Create `GlobalExceptionHandler.java`** — file: `source/src/main/java/com/example/mtiscores/exception/GlobalExceptionHandler.java`

Create the class `GlobalExceptionHandler` annotated with `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`. Implement method `handleMtiScoresException(MtiScoresException ex, HttpServletRequest request)` annotated with `@ExceptionHandler(MtiScoresException.class)` returning `ResponseEntity<ErrorResponseDto>`. Build `MetaDto` using `MDC.get("requestId")` (fall back to `UUID.randomUUID().toString()` if null) and `Instant.now().toString()`. Build `ErrorDataDto(ex.getErrorCode(), ex.getTitle(), ex.getMessage())`. Log at WARN: `log.warn("Client error code={} status={} message={} requestId={}", ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage(), MDC.get("requestId"))`. Return `ResponseEntity.status(ex.getHttpStatus()).body(new ErrorResponseDto(metaDto, errorDataDto))`. Implement a second method `handleGenericException(Exception ex, HttpServletRequest request)` annotated with `@ExceptionHandler(Exception.class)`. Log at ERROR: `log.error("Unexpected error requestId={}", MDC.get("requestId"), ex)`. Build `ErrorDataDto(ErrorCode.ERR_105, "Internal Server Error", "An unexpected error occurred")`. Return `ResponseEntity.status(500).body(new ErrorResponseDto(metaDto, errorDataDto))`.

**Complexity:** M | **Dependencies:** Story 4.1.a, Story 2.2, Story 2.1

---

### Epic 6: Cross-Cutting Concerns

**Goal:** Add request ID propagation via MDC and IP-based rate limiting via Bucket4j.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 6.1: Request ID Filter

**As a** developer **I want** a servlet filter that generates a UUID request ID per request **so that** every log line and every response `meta.request_id` is traceable.

**Background for implementer:** MDC (Mapped Diagnostic Context) is a SLF4J feature that attaches key-value pairs to all log lines emitted from the current thread. The filter must set `requestId` in MDC before any controller or service code runs, and must clear it in a `finally` block to prevent leakage across thread-pool reuse. The filter propagates the request ID as a response header `X-Request-Id` so clients can correlate responses.

**Acceptance Criteria:**
- [ ] Every request generates a UUID stored in `MDC` under key `"requestId"`
- [ ] Response header `X-Request-Id` contains the same UUID as `meta.request_id`
- [ ] MDC is cleared after each request in a `finally` block
- [ ] Filter order is `@Order(1)` — runs before `RateLimitFilter`

**Tasks:**

**Task 6.1.a — Create `RequestIdFilter.java`** — file: `source/src/main/java/com/example/mtiscores/filter/RequestIdFilter.java`

Create the class `RequestIdFilter` implementing `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_MDC_KEY = "requestId"`. In the `doFilter` method: generate `String requestId = UUID.randomUUID().toString()`; call `MDC.put(REQUEST_ID_MDC_KEY, requestId)`; cast `response` to `HttpServletResponse` and call `httpResponse.setHeader("X-Request-Id", requestId)`; wrap the `chain.doFilter(request, response)` call in try-finally; in the `finally` block call `MDC.remove(REQUEST_ID_MDC_KEY)`. No logger declaration is needed in this filter — MDC-enriched logging is handled by service and controller.

**Complexity:** S | **Dependencies:** None

---

#### Story 6.2: Rate Limiting Filter

**As a** developer **I want** a Bucket4j rate limiting filter that enforces 100 requests/minute per client IP **so that** the API is protected from abuse.

**Background for implementer:** Bucket4j implements the token-bucket algorithm in-process. Each client IP gets its own `Bucket` stored in a `LoadingCache<String, Bucket>` backed by Caffeine (see ADR-002). The cache is initialized in `RateLimitConfig`. The filter reads the client IP from `request.getRemoteAddr()`. When a bucket is exhausted, the filter writes a JSON error body directly to the response and returns without calling `chain.doFilter`. The rate limit value is read from `mti.rate-limit.requests-per-minute` so it can be overridden in tests.

**Acceptance Criteria:**
- [ ] The 101st request from the same IP within one minute receives HTTP 429
- [ ] Rate limit is configurable via `mti.rate-limit.requests-per-minute`
- [ ] `X-Request-Id` header is present on 429 responses (already set by `RequestIdFilter`)

**Tasks:**

**Task 6.2.a — Create `RateLimitConfig.java`** — file: `source/src/main/java/com/example/mtiscores/config/RateLimitConfig.java`

Create the class `RateLimitConfig` annotated with `@Configuration`. Inject `@Value("${mti.rate-limit.requests-per-minute:100}") int requestsPerMinute`. Declare a `@Bean` method `bucketCache()` returning `com.github.benmanes.caffeine.cache.LoadingCache<String, io.github.bucket4j.Bucket>`. Build the cache using `Caffeine.newBuilder().expireAfterAccess(2, TimeUnit.MINUTES).build(ip -> buildBucket())`. The private helper method `buildBucket()` creates a `Bucket` using `Bucket.builder().addLimit(Bandwidth.classic(requestsPerMinute, Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)))).build()`.

**Task 6.2.b — Create `RateLimitFilter.java`** — file: `source/src/main/java/com/example/mtiscores/filter/RateLimitFilter.java`

Create the class `RateLimitFilter` implementing `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(2)`. Inject `LoadingCache<String, Bucket> bucketCache` via constructor injection. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`. In the `doFilter` method: read `String clientIp = ((HttpServletRequest) request).getRemoteAddr()`; obtain `Bucket bucket = bucketCache.get(clientIp)`; call `bucket.tryConsume(1)`; if the result is false (rate limit exceeded), log at WARN `log.warn("Rate limit exceeded ip={} requestId={}", clientIp, MDC.get("requestId"))`, cast `response` to `HttpServletResponse`, set status 429, set content-type `application/json`, and write the following JSON string directly to `response.getWriter()`: `{"meta":{"request_id":"` + MDC.get("requestId") + `","request_timestamp":"` + Instant.now().toString() + `"},"data":{"error_code":"ERR_429","title":"Too Many Requests","message":"Rate limit exceeded. Maximum ` + requestsPerMinute + ` requests per minute."}}` then return without calling `chain.doFilter`; if token was consumed, call `chain.doFilter(request, response)`.

**Complexity:** M | **Dependencies:** Story 6.1, Story 1.1.c

---

### Epic 7: Testing

**Goal:** Achieve ≥80% test coverage with unit tests for business logic and integration tests that verify the full HTTP stack against a real PostgreSQL container.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 7.1: Service Unit Tests

**As a** developer **I want** unit tests for `MtiScoresService` **so that** business logic is verified in isolation without database dependencies.

**Acceptance Criteria:**
- [ ] Test for latest score routing (no year/month) passes
- [ ] Test for year-only routing passes
- [ ] Test for year+month routing passes
- [ ] Test for not-found case verifies `MtiScoresException` with code `ErrorCode.ERR_101` and HTTP status 404
- [ ] Tests use Mockito; no Spring context is loaded

**Tasks:**

**Task 7.1.a — Create `MtiScoresServiceTest.java`** — file: `source/src/test/java/com/example/mtiscores/service/MtiScoresServiceTest.java`

Create the class `MtiScoresServiceTest` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoresRepository repository` and `@InjectMocks MtiScoresService service`. Declare a private static helper method `buildRecord(String imo, int year, int month)` returning a `MtiScoreRecord` with: imoNumber=`imo`, year=`year`, month=`month`, mtiScore=`85.50`, vesselScore=`90.00`, reportingScore=`88.75`, voyagesScore=`82.30`, emissionsScore=`87.60`, sanctionsScore=`100.00`, createdAt=`OffsetDateTime.parse("2024-01-01T00:00:00Z")`, updatedAt=`OffsetDateTime.parse("2024-01-01T00:00:00Z")`. Test `getScores_noYearMonth_returnsLatest`: stub `repository.findLatest("9123456")` to return `Optional.of(buildRecord("9123456", 2024, 1))`; call `service.getScores("9123456", null, null, "test-req-1")`; assert `result.data().imoNumber()` equals `"9123456"`, `result.data().year()` equals `2024`, `result.data().month()` equals `1`, `result.data().scores().mtiScore()` equals `85.50`, `result.meta().requestId()` equals `"test-req-1"`. Test `getScores_yearOnly_returnsLatestForYear`: stub `repository.findByYear("9123456", 2023)` to return `Optional.of(buildRecord("9123456", 2023, 12))`; call `service.getScores("9123456", 2023, null, "test-req-2")`; assert `result.data().year()` equals `2023` and `result.data().month()` equals `12`. Test `getScores_yearAndMonth_returnsExact`: stub `repository.findByYearAndMonth("9123456", 2023, 6)` to return `Optional.of(buildRecord("9123456", 2023, 6))`; call `service.getScores("9123456", 2023, 6, "test-req-3")`; assert `result.data().month()` equals `6`. Test `getScores_notFound_throwsMtiScoresException`: stub `repository.findLatest("9999999")` to return `Optional.empty()`; call `assertThrows(MtiScoresException.class, () -> service.getScores("9999999", null, null, "req"))`; verify `ex.getErrorCode()` equals `ErrorCode.ERR_101` and `ex.getHttpStatus()` equals `404`.

**Complexity:** M | **Dependencies:** Story 4.1

---

#### Story 7.2: Controller Unit Tests

**As a** developer **I want** MockMvc unit tests for `MtiScoresController` **so that** input validation logic is tested without a running server.

**Acceptance Criteria:**
- [ ] Invalid IMO format returns 400 with `ERR_103` in `$.data.error_code`
- [ ] Month without year returns 400 with `ERR_102`
- [ ] Invalid month value (13) returns 400 with `ERR_104`
- [ ] Invalid year value (1999) returns 400 with `ERR_104`
- [ ] Valid request returns 200 with `$.meta.request_id` and `$.data.imo_number`

**Tasks:**

**Task 7.2.a — Create `MtiScoresControllerTest.java`** — file: `source/src/test/java/com/example/mtiscores/controller/MtiScoresControllerTest.java`

Create the class `MtiScoresControllerTest` annotated with `@WebMvcTest(MtiScoresController.class)`. Declare `@MockBean MtiScoresService service`. In `@BeforeEach` call `MDC.put("requestId", "test-request-id")`; in `@AfterEach` call `MDC.clear()`. Inject `@Autowired MockMvc mockMvc`. Test `invalidImo_returns400_ERR103`: perform `GET /api/v1/vessels/123/mti-scores`, assert HTTP status 400, assert JSON path `$.data.error_code` equals `"ERR_103"`. Test `monthWithoutYear_returns400_ERR102`: perform `GET /api/v1/vessels/9123456/mti-scores?month=6`, assert status 400, assert `$.data.error_code` equals `"ERR_102"`. Test `invalidMonthValue_returns400_ERR104`: perform `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13`, assert status 400, assert `$.data.error_code` equals `"ERR_104"`. Test `invalidYearTooLow_returns400_ERR104`: perform `GET /api/v1/vessels/9123456/mti-scores?year=1999`, assert status 400, assert `$.data.error_code` equals `"ERR_104"`. Test `validRequest_returns200`: stub `service.getScores(eq("9123456"), isNull(), isNull(), any())` to return `new SuccessResponseDto(new MetaDto("test-request-id", "2024-01-15T10:30:00Z"), new VesselDataDto("9123456", 2024, 1, new ScoresDto(85.50, 90.00, 88.75, 82.30, 87.60, 100.00), new MetadataDto("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")))`; perform `GET /api/v1/vessels/9123456/mti-scores`; assert status 200; assert `$.meta.request_id` equals `"test-request-id"`; assert `$.data.imo_number` equals `"9123456"`; assert `$.data.scores.mti_score` equals `85.5`.

**Complexity:** M | **Dependencies:** Story 5.1, Story 5.2

---

#### Story 7.3: Integration Tests with Testcontainers

**As a** developer **I want** integration tests that start a real PostgreSQL container **so that** the full HTTP-to-database flow is verified end-to-end.

**Background for implementer:** Testcontainers spins up a Docker container running PostgreSQL. `@DynamicPropertySource` injects the container's JDBC URL, username, and password into the Spring context before it starts, overriding the blank values in `application-test.yml`. `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)` starts a real embedded Tomcat. `TestRestTemplate` makes actual HTTP calls to the running server. Flyway applies both `V1__create_mti_scores_history.sql` and `V2__seed_test_data.sql` on startup.

**Acceptance Criteria:**
- [ ] AC1: GET without filters for `9123456` returns 200 with `data.year=2024`
- [ ] AC2: GET with `?year=2023` for `9123456` returns 200 with `data.month=12`
- [ ] AC3: GET with `?year=2023&month=6` returns 200 with `data.month=6`
- [ ] AC4: GET for IMO `9999999` returns 404 with `data.error_code="ERR_101"`
- [ ] AC5: GET for IMO `123` returns 400 with `data.error_code="ERR_103"`
- [ ] AC6: GET with `?month=6` (no year) returns 400 with `data.error_code="ERR_102"`
- [ ] AC7: GET with `?year=2023&month=13` returns 400 with `data.error_code="ERR_104"`
- [ ] AC8: GET for IMO `8000001` returns 200 with all score fields as `null`

**Tasks:**

**Task 7.3.a — Create `MtiScoresIntegrationTest.java`** — file: `source/src/test/java/com/example/mtiscores/integration/MtiScoresIntegrationTest.java`

Create the class `MtiScoresIntegrationTest` annotated with `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` and `@ActiveProfiles("test")`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Annotate a `@DynamicPropertySource static void configureProperties(DynamicPropertyRegistry registry)` method that registers `spring.datasource.url` using `postgres::getJdbcUrl`, `spring.datasource.username` using `postgres::getUsername`, `spring.datasource.password` using `postgres::getPassword`. Inject `@Autowired TestRestTemplate restTemplate` and `@LocalServerPort int port`. Define private helper `url(String path)` returning `"http://localhost:" + port + path`. Test `ac1_latestScores`: perform GET to `url("/api/v1/vessels/9123456/mti-scores")`, assert HTTP 200, parse body as `Map`, assert `((Map) body.get("data")).get("imo_number")` equals `"9123456"`, `((Map) body.get("data")).get("year")` equals `2024`, `((Map)((Map) body.get("data")).get("scores")).get("mti_score")` equals `85.5`. Test `ac2_yearFilter`: GET `url("/api/v1/vessels/9123456/mti-scores?year=2023")`, assert status 200, assert `data.month` equals `12`. Test `ac3_yearAndMonth`: GET `url("/api/v1/vessels/9123456/mti-scores?year=2023&month=6")`, assert status 200, assert `data.month` equals `6`. Test `ac4_notFound`: GET `url("/api/v1/vessels/9999999/mti-scores")`, assert status 404, assert `data.error_code` equals `"ERR_101"`. Test `ac5_invalidImo`: GET `url("/api/v1/vessels/123/mti-scores")`, assert status 400, assert `data.error_code` equals `"ERR_103"`. Test `ac6_monthWithoutYear`: GET `url("/api/v1/vessels/9123456/mti-scores?month=6")`, assert status 400, assert `data.error_code` equals `"ERR_102"`. Test `ac7_invalidMonth`: GET `url("/api/v1/vessels/9123456/mti-scores?year=2023&month=13")`, assert status 400, assert `data.error_code` equals `"ERR_104"`. Test `ac8_nullScores`: GET `url("/api/v1/vessels/8000001/mti-scores")`, assert status 200, assert `((Map)((Map) body.get("data")).get("scores")).get("mti_score")` is null.

**Complexity:** L | **Dependencies:** Story 1.2, Story 5.1, Story 5.2, Story 6.1, Story 6.2

---

### Epic 8: Containerization

**Goal:** Package the application in a Docker image and provide a docker-compose for local development.
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 8.1: Docker Setup

**As a** developer **I want** a Dockerfile and docker-compose **so that** the service and its PostgreSQL dependency can be run locally with a single command.

**Acceptance Criteria:**
- [ ] `docker build -t mti-scores-api .` succeeds from `source/`
- [ ] `docker-compose up` starts the app and PostgreSQL; `GET /actuator/health` returns `{"status":"UP"}`
- [ ] Dockerfile uses multi-stage build to minimize image size

**Tasks:**

**Task 8.1.a — Create `Dockerfile`** — file: `source/Dockerfile`

Create a two-stage Dockerfile. Stage 1 (`builder`) uses `maven:3.9-eclipse-temurin-17` as base image, sets `WORKDIR /build`, copies `pom.xml` first, runs `mvn dependency:go-offline -B` to cache dependencies, then copies `src/` and runs `mvn clean package -DskipTests -B`. Stage 2 uses `eclipse-temurin:17-jre-alpine`, sets `WORKDIR /app`, copies `--from=builder /build/target/mti-scores-api-1.0.0-SNAPSHOT.jar` as `app.jar`. Expose port `8080`. Set `ENTRYPOINT ["java", "-jar", "app.jar"]`. Declare `ENV DATABASE_URL=""`, `ENV DATABASE_USERNAME=""`, `ENV DATABASE_PASSWORD=""` as empty defaults to document required environment variables.

**Task 8.1.b — Create `docker-compose.yml`** — file: `source/docker-compose.yml`

Create a docker-compose file with two services: `postgres` using image `postgres:15-alpine` with environment `POSTGRES_DB=mtidb`, `POSTGRES_USER=mtiuser`, `POSTGRES_PASSWORD=mtipass`, port `5432:5432`, and named volume `postgres_data`. Service `mti-scores-api` built from `Dockerfile` with environment `DATABASE_URL=jdbc:postgresql://postgres:5432/mtidb`, `DATABASE_USERNAME=mtiuser`, `DATABASE_PASSWORD=mtipass`, port `8080:8080`, and `depends_on: [postgres]`. Declare the named volume `postgres_data` at the top level.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     string    Required   7-digit numeric string; must match ^[0-9]{7}$

Query Parameters:
  year    integer   Optional   Filter by year; must be 2000–2100
  month   integer   Optional   Filter by month (1–12); requires year to be present

Request Headers:
  (none required)

Success Response — 200 OK:
  meta.request_id                  string (UUID v4)    Unique request identifier
  meta.request_timestamp           string (ISO 8601)   UTC timestamp of the request
  data.imo_number                  string              7-digit vessel IMO number
  data.year                        integer             Year of the score record
  data.month                       integer             Month of the score record (1–12)
  data.scores.mti_score            number|null         Overall MTI score
  data.scores.vessel_score         number|null         Vessel sub-score
  data.scores.reporting_score      number|null         Reporting sub-score
  data.scores.voyages_score        number|null         Voyages sub-score
  data.scores.emissions_score      number|null         Emissions sub-score
  data.scores.sanctions_score      number|null         Sanctions sub-score
  data.metadata.created_at         string (ISO 8601)   Record creation timestamp
  data.metadata.updated_at         string (ISO 8601)   Record last-updated timestamp

Error Response — 4XX / 5XX:
  meta.request_id                  string (UUID v4)
  meta.request_timestamp           string (ISO 8601)
  data.error_code                  string
  data.title                       string
  data.message                     string

Error Code Reference:
  ERR_101   404   No MTI scores found for the given IMO/year/month combination
  ERR_102   400   Month specified without year
  ERR_103   400   IMO number is not a 7-digit numeric string
  ERR_104   400   Year outside 2000–2100 or month outside 1–12
  ERR_105   500   Unhandled internal error (database failure, etc.)
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | Index on `(imo_number, year DESC, month DESC)` for sub-10ms DB queries; p95 target < 100ms end-to-end |
| Logging | SLF4J + Logback; MDC field `requestId` on every line; INFO for request entry/exit, WARN for client errors and rate limit, ERROR for server errors |
| Metrics | Spring Boot Actuator `/actuator/health`; extend with Micrometer + Prometheus if monitoring required |
| Security | Parameterized queries via JdbcTemplate (SQL injection prevention); IMO regex validation at controller; no sensitive data in error messages |
| Rate Limiting | Bucket4j token-bucket; 100 req/min per client IP; configurable via `mti.rate-limit.requests-per-minute`; returns HTTP 429 on exhaustion |
| Testing | ≥80% line coverage; unit tests (no Spring context) for service + controller; Testcontainers integration tests for full HTTP-to-DB stack |
| Health / Docs | `/actuator/health` endpoint; OpenAPI spec embedded in `docs/prd.md` |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `MtiScoresController` | `MtiScoresService` | Delegates all business logic to service |
| `MtiScoresController` | `ErrorCode` | References `ERR_102`, `ERR_103`, `ERR_104` for validation errors |
| `MtiScoresController` | `MtiScoresException` | Throws domain exceptions for validation failures |
| `MtiScoresService` | `MtiScoresRepository` | Calls repository query methods |
| `MtiScoresService` | `ErrorCode.ERR_101` | Uses constant when record not found |
| `MtiScoresService` | `MtiScoresException` | Throws exception on not-found |
| `MtiScoresService` | `MetaDto`, `ScoresDto`, `MetadataDto`, `VesselDataDto`, `SuccessResponseDto` | Constructs response envelope |
| `MtiScoresRepository` | `MtiScoreRecord` | Returns domain model from ResultSet |
| `GlobalExceptionHandler` | `MtiScoresException` | Handles domain exceptions |
| `GlobalExceptionHandler` | `ErrorCode.ERR_105` | Uses constant for generic errors |
| `GlobalExceptionHandler` | `ErrorResponseDto`, `ErrorDataDto`, `MetaDto` | Constructs error response envelope |
| `RateLimitFilter` | `RateLimitConfig` (bucketCache bean) | Gets per-IP Bucket instance |
| `RateLimitFilter` | `RequestIdFilter.REQUEST_ID_MDC_KEY` | Reads `requestId` from MDC for 429 response body |
| `RequestIdFilter` | `MDC` | Sets and clears `requestId` key per request |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Foundation; nothing else compiles without the POM and application entry point
2. **Story 2.1** — Error constants needed by all other layers; no dependencies
3. **Story 2.2** — Domain model and DTOs needed by repository, service, and controller
4. **Story 1.2** — Database schema must exist before repository queries can be developed
5. **Story 3.1** — Repository depends on domain model and DB schema
6. **Story 4.1** — Service depends on repository and DTOs
7. **Story 5.1** — Controller depends on service and error codes
8. **Story 5.2** — Exception handler depends on `MtiScoresException` and DTOs
9. **Story 6.1** — Request ID filter has no business logic dependencies; can run in parallel with Stories 5.x
10. **Story 6.2** — Rate limit filter depends on `RateLimitConfig` and MDC key from `RequestIdFilter`
11. **Story 7.1** — Service unit tests require service layer completion
12. **Story 7.2** — Controller tests require controller + exception handler
13. **Story 7.3** — Integration tests require the full stack including filters
14. **Story 8.1** — Containerization is independent; can run in parallel with Stories 7.x

> Stories 2.1, 2.2, and 1.2 can be developed in parallel once Story 1.1 is complete. Stories 6.1 and 8.1 can proceed independently once the project structure exists.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `MetaDto` | `requestId: String`, `requestTimestamp: String` | `request_id`, `request_timestamp` | UUID v4 string, ISO 8601 UTC string |
| `ScoresDto` | `mtiScore: Double`, `vesselScore: Double`, `reportingScore: Double`, `voyagesScore: Double`, `emissionsScore: Double`, `sanctionsScore: Double` | `mti_score`, `vessel_score`, `reporting_score`, `voyages_score`, `emissions_score`, `sanctions_score` | All nullable; `@JsonInclude(ALWAYS)` preserves null |
| `MetadataDto` | `createdAt: String`, `updatedAt: String` | `created_at`, `updated_at` | ISO 8601 strings |
| `VesselDataDto` | `imoNumber: String`, `year: Integer`, `month: Integer`, `scores: ScoresDto`, `metadata: MetadataDto` | `imo_number`, `year`, `month`, `scores`, `metadata` | Data section of success response |
| `SuccessResponseDto` | `meta: MetaDto`, `data: VesselDataDto` | `meta`, `data` | Top-level success envelope |
| `ErrorDataDto` | `errorCode: String`, `title: String`, `message: String` | `error_code`, `title`, `message` | Error payload |
| `ErrorResponseDto` | `meta: MetaDto`, `data: ErrorDataDto` | `meta`, `data` | Top-level error envelope |
| `MtiScoreRecord` | `imoNumber: String`, `year: int`, `month: int`, `mtiScore: Double`, `vesselScore: Double`, `reportingScore: Double`, `voyagesScore: Double`, `emissionsScore: Double`, `sanctionsScore: Double`, `createdAt: OffsetDateTime`, `updatedAt: OffsetDateTime` | — | Internal domain model; not serialized directly |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | Yes | `jdbc:postgresql://localhost:5432/mtidb` | PostgreSQL JDBC connection URL |
| `DATABASE_USERNAME` | Yes | `mtiuser` | PostgreSQL username |
| `DATABASE_PASSWORD` | Yes | `mtipass` | PostgreSQL password |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | — |
| `year` | INTEGER | No | — |
| `month` | INTEGER | No | — |
| `mti_score` | NUMERIC(6,2) | Yes | — |
| `vessel_score` | NUMERIC(6,2) | Yes | — |
| `reporting_score` | NUMERIC(6,2) | Yes | — |
| `voyages_score` | NUMERIC(6,2) | Yes | — |
| `emissions_score` | NUMERIC(6,2) | Yes | — |
| `sanctions_score` | NUMERIC(6,2) | Yes | — |
| `created_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |

Additional constraints:
- Unique constraint `uq_mti_scores_imo_year_month` on `(imo_number, year, month)`
- Index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` for optimal query performance

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| PostgreSQL unavailable at startup | M | H | Spring Boot health check fails fast; `depends_on` in docker-compose for local dev |
| Rate limit bypassed via IP spoofing (X-Forwarded-For) | M | M | Use `request.getRemoteAddr()` (trusted proxy); document that reverse proxy is needed in production to set real client IP |
| Null score fields cause NullPointerException | L | M | Use boxed `Double` throughout; `@JsonInclude(ALWAYS)` ensures null fields serialize correctly |
| Testcontainers unavailable in CI (no Docker daemon) | M | M | Require Docker-in-Docker in CI pipeline; document in README |
| Token-bucket resets on app restart (in-memory Bucket4j) | L | L | Acceptable for MVP; document path to Redis-backed Bucket4j for production |

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
- [ ] Performance targets verified under load
- [ ] Security review passed
- [ ] API documentation updated (OpenAPI spec in `docs/prd.md`)
- [ ] Docker image builds and runs locally
- [ ] Environment variables documented

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven project setup; nothing else compiles without this
2. **Story 2.1** — Error constants; no dependencies, needed by all layers
3. **Story 2.2** — Domain model and DTOs; can run in parallel with Story 1.2
4. **Story 1.2** — Database schema migration; can run in parallel with Story 2.2
5. **Story 3.1** — Repository layer; requires Stories 1.2 and 2.2
6. **Story 4.1** — Service layer; requires Story 3.1
7. **Story 5.1** — Controller; requires Story 4.1
8. **Story 5.2** — Global exception handler; requires Story 4.1.a (MtiScoresException)
9. **Story 6.1** — Request ID filter; can run in parallel with Stories 5.x
10. **Story 6.2** — Rate limit filter; requires Story 6.1 and Story 1.1.c
11. **Story 7.1** — Service unit tests; requires Story 4.1
12. **Story 7.2** — Controller unit tests; requires Stories 5.1 and 5.2
13. **Story 7.3** — Integration tests; requires the full stack (Stories 1–6)
14. **Story 8.1** — Dockerization; can run in parallel with Stories 7.x

> Parallel tracks: Stories 2.1, 2.2, and 1.2 after Story 1.1. Stories 6.1 and 8.1 can proceed independently once the project structure is in place.
