# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The MTI Scores API exposes a single REST endpoint (`GET /api/v1/vessels/{imo}/mti-scores`) that retrieves Maritime Transportation Indicator (MTI) scores for a vessel identified by its 7-digit IMO number, with optional filtering by year and/or year+month. The API reads from a PostgreSQL table `mti_scores_history` using three distinct query strategies depending on which filters are supplied. All responses share a uniform envelope with a `meta` block (request UUID + timestamp) and a `data` block (scores or error details). The implementation is a greenfield Spring Boot 3 application with Flyway migrations, Bucket4j rate limiting, SLF4J/MDC-based request tracing, and comprehensive unit and integration tests via Testcontainers.

---

## TECHNICAL ANALYSIS

### Recommended Stack

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 21 | LTS release, virtual threads available, Spring Boot 3 baseline |
| Build | Maven 3.9 | Standard enterprise build tool; dependency management via BOM |
| Framework | Spring Boot 3.2 | Production-ready auto-configuration, validation, web, data |
| ORM | Spring Data JPA + Hibernate 6 | Named query methods cover all three query patterns without custom SQL |
| Database | PostgreSQL 15 | Required by PRD; JSONB, window functions available if needed later |
| Migrations | Flyway 10 | Versioned SQL migrations; auto-applied on startup |
| Rate Limiting | Bucket4j 8.10 (in-memory) | Token-bucket algorithm; zero external dependency for single-instance |
| Validation | Jakarta Bean Validation 3 (Hibernate Validator) | Declarative constraint annotations on controller parameters |
| Logging | SLF4J + Logback | MDC-based request tracing; structured log output |
| Testing | JUnit 5 + Mockito + Testcontainers 1.19 | Unit tests for service; integration tests with real PostgreSQL container |

### Project Structure

```
source/
├── src/
│   ├── main/
│   │   ├── java/com/polestar/mti/
│   │   │   ├── MtiScoresApplication.java
│   │   │   ├── config/
│   │   │   │   ├── RateLimitConfig.java
│   │   │   │   └── JacksonConfig.java
│   │   │   ├── constant/
│   │   │   │   └── ErrorCode.java
│   │   │   ├── controller/
│   │   │   │   └── MtiScoreController.java
│   │   │   ├── dto/
│   │   │   │   ├── ApiResponse.java
│   │   │   │   ├── MetaDto.java
│   │   │   │   ├── MtiScoreDataDto.java
│   │   │   │   ├── ScoresDto.java
│   │   │   │   ├── MetadataDto.java
│   │   │   │   └── ErrorDataDto.java
│   │   │   ├── entity/
│   │   │   │   └── MtiScore.java
│   │   │   ├── exception/
│   │   │   │   ├── MtiException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── filter/
│   │   │   │   ├── RequestIdFilter.java
│   │   │   │   └── RateLimitFilter.java
│   │   │   ├── repository/
│   │   │   │   └── MtiScoreRepository.java
│   │   │   └── service/
│   │   │       └── MtiScoreService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__create_mti_scores_history.sql
│   └── test/
│       ├── java/com/polestar/mti/
│       │   ├── service/
│       │   │   └── MtiScoreServiceTest.java
│       │   └── controller/
│       │       └── MtiScoreControllerIntegrationTest.java
│       └── resources/
│           └── application-test.yml
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

### Integration Points

- PostgreSQL 15 database instance (connection pool via HikariCP, configured in `application.yml`)
- No external HTTP APIs
- Rate limiting state held in JVM memory (ConcurrentHashMap); not shared across instances — suitable for single-node deployment

### Technical Constraints

- IMO numbers must match regex `^[0-9]{7}$` — enforced at controller layer before any DB call
- Year must be in range 2000–2100; month must be in range 1–12 — enforced via Bean Validation
- Month without year is a domain-level validation error (ERR_102), not a Bean Validation violation — handled in service
- Rate limit: 100 requests per minute per `X-Api-Key` header value; exceed returns HTTP 429
- All score fields are nullable in the DB and must be returned as JSON `null` (not omitted)
- Request ID (UUID v4) must be present in every response under `meta.request_id` and in `X-Request-Id` response header
- All timestamps in ISO 8601 UTC format

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.polestar.mti` | **Group ID:** `com.polestar` | **Artifact ID:** `mti-scores-api`

### Overview

The backend consists of nine implementation layers: Maven project skeleton, Flyway database migration, JPA entity, repository, DTOs/constants, service, filters (request ID and rate limiting), controller, and exception handler. Each layer is strictly independent of layers above it in the dependency chain. All business logic lives in `MtiScoreService`; the controller is a thin adapter that delegates validation results to the service and maps exceptions to responses via `GlobalExceptionHandler`.

---

### Epic 1: Project Bootstrap & Configuration

**Goal:** Establish a runnable Spring Boot application skeleton with all required dependencies declared, application properties configured, and Docker infrastructure in place.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Maven Project Setup

**As a** developer **I want** a fully configured `pom.xml` **so that** all dependencies are resolved and the project compiles cleanly.

**Acceptance Criteria:**
- [ ] `pom.xml` at `source/pom.xml` compiles with `mvn clean package -DskipTests`
- [ ] Spring Boot parent BOM `3.2.x` is declared
- [ ] All required dependency artifact IDs are present with correct versions
- [ ] Maven Surefire 3.x is configured to run JUnit 5 tests

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `source/pom.xml`

Create the Maven POM with `groupId` `com.polestar`, `artifactId` `mti-scores-api`, `version` `0.0.1-SNAPSHOT`, `packaging` `jar`, parent `org.springframework.boot:spring-boot-starter-parent:3.2.5`. Declare the following dependencies: `org.springframework.boot:spring-boot-starter-web` (no version, managed by BOM), `org.springframework.boot:spring-boot-starter-data-jpa`, `org.springframework.boot:spring-boot-starter-validation`, `org.postgresql:postgresql` (runtime scope), `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, `com.github.ben-manes.caffeine:caffeine:3.1.8` (required by Bucket4j cache abstraction), `com.bucket4j:bucket4j-core:8.10.1`, `org.springframework.boot:spring-boot-starter-test` (test scope), `org.testcontainers:junit-jupiter:1.19.8` (test scope), `org.testcontainers:postgresql:1.19.8` (test scope). Add the `spring-boot-maven-plugin` for executable JAR packaging.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Application Entry Point and Configuration

**As a** developer **I want** the Spring Boot main class and `application.yml` **so that** the application starts and connects to PostgreSQL.

**Acceptance Criteria:**
- [ ] `MtiScoresApplication` class annotated with `@SpringBootApplication` in package `com.polestar.mti`
- [ ] `application.yml` configures datasource, JPA, Flyway, and server port
- [ ] All config keys use the exact names referenced by other tasks

**Tasks:**

**Task 1.2.a — Create MtiScoresApplication** — file: `source/src/main/java/com/polestar/mti/MtiScoresApplication.java`

Create class `MtiScoresApplication` in package `com.polestar.mti`, annotated with `@SpringBootApplication`. Implement a standard `public static void main(String[] args)` method that calls `SpringApplication.run(MtiScoresApplication.class, args)`. No additional beans or configuration belong in this class.

**Task 1.2.b — Create application.yml** — file: `source/src/main/resources/application.yml`

Create the main application configuration file with the following keys and values. Under `server`: set `port` to `8080`. Under `spring.datasource`: set `url` to `${DATABASE_URL:jdbc:postgresql://localhost:5432/mtidb}`, `username` to `${DATABASE_USERNAME:mti}`, `password` to `${DATABASE_PASSWORD:mti}`, `driver-class-name` to `org.postgresql.Driver`. Under `spring.jpa`: set `hibernate.ddl-auto` to `validate` (Flyway owns the schema), `show-sql` to `false`, `open-in-view` to `false`. Under `spring.flyway`: set `enabled` to `true`, `locations` to `classpath:db/migration`. Under `app.rate-limit`: set `requests-per-minute` to `100` (this key is read by `RateLimitConfig`). Under `logging.pattern.console`: use `%d{yyyy-MM-dd HH:mm:ss} [%X{requestId}] %-5level %logger{36} - %msg%n` to embed the MDC `requestId` field in every log line.

**Task 1.2.c — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create a `docker-compose.yml` with two services: `db` using image `postgres:15-alpine`, environment variables `POSTGRES_DB=mtidb`, `POSTGRES_USER=mti`, `POSTGRES_PASSWORD=mti`, port mapping `5432:5432`, and a named volume `pgdata` mounted at `/var/lib/postgresql/data`; and `app` using `build: .`, environment variables `DATABASE_URL=jdbc:postgresql://db:5432/mtidb`, `DATABASE_USERNAME=mti`, `DATABASE_PASSWORD=mti`, port `8080:8080`, with `depends_on: [db]`.

**Task 1.2.d — Create Dockerfile** — file: `source/Dockerfile`

Create a two-stage Dockerfile. Stage 1 (`builder`) uses `eclipse-temurin:21-jdk-alpine`, copies `pom.xml` and the `src` directory, runs `./mvnw clean package -DskipTests` (include the `.mvn` wrapper directory as well), and produces `target/mti-scores-api-0.0.1-SNAPSHOT.jar`. Stage 2 uses `eclipse-temurin:21-jre-alpine`, copies the JAR from stage 1 as `app.jar`, and sets the entry point to `java -jar /app.jar`. Expose port `8080`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Database Foundation

**Goal:** Define the PostgreSQL schema through a Flyway migration and map it to a JPA entity.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 2.1: Database Schema Migration

**As a** developer **I want** the `mti_scores_history` table created via Flyway **so that** JPA validation passes on startup.

**Background for implementer:** Flyway applies versioned scripts in `classpath:db/migration` automatically on application startup. The script must be idempotent — do not use `DROP TABLE IF EXISTS` in a `V1` script; Flyway tracks applied versions in `flyway_schema_history`. The composite index `idx_mti_scores_imo_year_month` on columns `(imo_number, year DESC, month DESC)` is required for the three query patterns to perform efficiently per the PRD.

**Acceptance Criteria:**
- [ ] Migration file named exactly `V1__create_mti_scores_history.sql` exists in `db/migration`
- [ ] Table `mti_scores_history` has all 11 columns with correct types and nullability
- [ ] Composite index `idx_mti_scores_imo_year_month` is created on `(imo_number, year, month)`
- [ ] `spring.jpa.hibernate.ddl-auto=validate` passes on application startup

**Tasks:**

**Task 2.1.a — Create Flyway migration V1** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Write a SQL script that creates table `mti_scores_history` with the following columns: `id` as `BIGSERIAL PRIMARY KEY`; `imo_number` as `VARCHAR(7) NOT NULL`; `year` as `INTEGER NOT NULL`; `month` as `INTEGER NOT NULL`; `mti_score` as `NUMERIC(10,2)` nullable; `vessel_score` as `NUMERIC(10,2)` nullable; `reporting_score` as `NUMERIC(10,2)` nullable; `voyages_score` as `NUMERIC(10,2)` nullable; `emissions_score` as `NUMERIC(10,2)` nullable; `sanctions_score` as `NUMERIC(10,2)` nullable; `created_at` as `TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`; `updated_at` as `TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`. After the `CREATE TABLE`, add `CREATE UNIQUE INDEX idx_mti_scores_imo_year_month_unique ON mti_scores_history (imo_number, year, month)` to prevent duplicate records for the same vessel/year/month combination. Add `CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC)` for the ordering queries. Both indexes must be created in the same migration file.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 2.2: MtiScore JPA Entity

**As a** developer **I want** a JPA entity class mapping to `mti_scores_history` **so that** Spring Data JPA can generate repository queries.

**Acceptance Criteria:**
- [ ] Class `MtiScore` in package `com.polestar.mti.entity` maps to table `mti_scores_history`
- [ ] All 11 columns are mapped with correct field names, types, and nullability rules
- [ ] `@Column(nullable = false)` is present on non-nullable columns
- [ ] `createdAt` and `updatedAt` map to `OffsetDateTime` (preserves timezone from `TIMESTAMP WITH TIME ZONE`)

**Tasks:**

**Task 2.2.a — Create MtiScore entity** — file: `source/src/main/java/com/polestar/mti/entity/MtiScore.java`

Create class `MtiScore` in package `com.polestar.mti.entity` annotated with `@Entity` and `@Table(name = "mti_scores_history")`. Annotate the class with `@Getter` and `@Setter` from Lombok (add Lombok dependency `org.projectlombok:lombok` to `pom.xml` with `optional=true` and the annotation processor configuration). Declare field `Long id` annotated with `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)`. Declare `String imoNumber` annotated with `@Column(name = "imo_number", nullable = false, length = 7)`. Declare `Integer year` annotated with `@Column(name = "year", nullable = false)`. Declare `Integer month` annotated with `@Column(name = "month", nullable = false)`. Declare `BigDecimal mtiScore` annotated with `@Column(name = "mti_score")` (nullable by default). Declare `BigDecimal vesselScore` with `@Column(name = "vessel_score")`. Declare `BigDecimal reportingScore` with `@Column(name = "reporting_score")`. Declare `BigDecimal voyagesScore` with `@Column(name = "voyages_score")`. Declare `BigDecimal emissionsScore` with `@Column(name = "emissions_score")`. Declare `BigDecimal sanctionsScore` with `@Column(name = "sanctions_score")`. Declare `OffsetDateTime createdAt` with `@Column(name = "created_at", nullable = false)`. Declare `OffsetDateTime updatedAt` with `@Column(name = "updated_at", nullable = false)`. Use `BigDecimal` (not `Double`) for all score fields to avoid floating-point precision loss when reading `NUMERIC(10,2)` from PostgreSQL.

**Complexity:** S | **Dependencies:** Story 2.1

---

### Epic 3: Repository Layer

**Goal:** Provide the three query methods required by the service layer using Spring Data JPA derived query naming.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 3.1: MtiScoreRepository

**As a** developer **I want** a Spring Data JPA repository with three named query methods **so that** the service can retrieve scores using any combination of filters.

**Background for implementer:** Spring Data JPA derives SQL from method names using the `findTop...By...OrderBy...` convention. `findTopByImoNumberOrderByYearDescMonthDesc` translates to `SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1`, which matches the PRD's "latest scores" query exactly. Similarly, `findTopByImoNumberAndYearOrderByMonthDesc` covers the "filter by year" query, and `findByImoNumberAndYearAndMonth` covers the "specific year+month" query. No `@Query` annotations are required.

**Acceptance Criteria:**
- [ ] Interface `MtiScoreRepository` extends `JpaRepository<MtiScore, Long>`
- [ ] All three query methods return `Optional<MtiScore>`
- [ ] Method names match the derived query convention so no `@Query` is needed

**Tasks:**

**Task 3.1.a — Create MtiScoreRepository** — file: `source/src/main/java/com/polestar/mti/repository/MtiScoreRepository.java`

Create interface `MtiScoreRepository` in package `com.polestar.mti.repository` that extends `JpaRepository<MtiScore, Long>`. Declare three methods: (1) `Optional<MtiScore> findTopByImoNumberOrderByYearDescMonthDesc(String imoNumber)` — returns the single most recent record for the given IMO regardless of year/month; (2) `Optional<MtiScore> findTopByImoNumberAndYearOrderByMonthDesc(String imoNumber, Integer year)` — returns the most recent record in the specified year; (3) `Optional<MtiScore> findByImoNumberAndYearAndMonth(String imoNumber, Integer year, Integer month)` — returns the exact record for the given year and month. No annotations beyond the interface declaration are required; Spring Data JPA discovers it automatically via the `@SpringBootApplication` component scan.

**Complexity:** S | **Dependencies:** Story 2.2

---

### Epic 4: DTOs and Constants

**Goal:** Define all response envelope types, error codes, and the domain exception so other layers can reference them.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 4.1: ErrorCode Enum and MtiException

**As a** developer **I want** an `ErrorCode` enum and a `MtiException` class **so that** the service and exception handler share a single source of truth for error codes, HTTP statuses, and titles.

**Acceptance Criteria:**
- [ ] `ErrorCode` enum has exactly 5 entries: `ERR_101` through `ERR_105` with correct titles and HTTP statuses
- [ ] `MtiException` is an unchecked exception carrying an `ErrorCode` and an optional detail message
- [ ] `ErrorCode.getHttpStatus()` returns a `org.springframework.http.HttpStatus`

**Tasks:**

**Task 4.1.a — Create ErrorCode enum** — file: `source/src/main/java/com/polestar/mti/constant/ErrorCode.java`

Create enum `ErrorCode` in package `com.polestar.mti.constant`. Each constant carries two constructor arguments: a `String title` and an `HttpStatus httpStatus` (from `org.springframework.http`). Declare five constants: `ERR_101("Resource Not Found", HttpStatus.NOT_FOUND)`, `ERR_102("Invalid Parameters", HttpStatus.BAD_REQUEST)`, `ERR_103("Invalid IMO Format", HttpStatus.BAD_REQUEST)`, `ERR_104("Invalid Date Range", HttpStatus.BAD_REQUEST)`, `ERR_105("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR)`. Provide getter methods `getTitle()` returning `String` and `getHttpStatus()` returning `HttpStatus`. Provide `getCode()` returning `this.name()` so callers can get the string `"ERR_101"` without hardcoding it.

**Task 4.1.b — Create MtiException** — file: `source/src/main/java/com/polestar/mti/exception/MtiException.java`

Create class `MtiException` in package `com.polestar.mti.exception` extending `RuntimeException`. Declare field `final ErrorCode errorCode`. Provide two constructors: `MtiException(ErrorCode errorCode, String message)` which calls `super(message)` and assigns `this.errorCode = errorCode`; and `MtiException(ErrorCode errorCode)` which delegates to `this(errorCode, errorCode.getTitle())`. Provide getter `getErrorCode()` returning `ErrorCode`. This exception is thrown only by `MtiScoreService` and caught only by `GlobalExceptionHandler`.

**Complexity:** S | **Dependencies:** None

---

#### Story 4.2: Response DTOs

**As a** developer **I want** all response DTO classes **so that** the controller and exception handler can construct properly shaped JSON responses.

**Background for implementer:** The PRD requires snake_case JSON keys (`request_id`, `imo_number`, etc.) while Java uses camelCase field names. The cleanest approach is to annotate each DTO field with `@JsonProperty("snake_case_name")` explicitly. This avoids a global Jackson `PropertyNamingStrategy` that could interfere with Spring MVC's own serialization of Spring types (e.g., `ProblemDetail`). Every DTO must be an immutable record or use `@AllArgsConstructor` + `@Getter` from Lombok to avoid mutable state.

**Acceptance Criteria:**
- [ ] `ApiResponse<T>` serializes to `{"meta": {...}, "data": {...}}`
- [ ] `MetaDto` serializes `requestId` as `"request_id"` and `requestTimestamp` as `"request_timestamp"`
- [ ] `MtiScoreDataDto` serializes `imoNumber` as `"imo_number"`
- [ ] `ScoresDto` score fields are nullable and serialize to JSON `null` (not omitted) when null
- [ ] `ErrorDataDto` serializes `errorCode` as `"error_code"`

**Tasks:**

**Task 4.2.a — Create ApiResponse** — file: `source/src/main/java/com/polestar/mti/dto/ApiResponse.java`

Create generic class `ApiResponse<T>` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor` from Lombok. Declare two fields: `MetaDto meta` and `T data`. No `@JsonProperty` is needed on these two fields since the names `meta` and `data` are already lowercase and match the JSON contract.

**Task 4.2.b — Create MetaDto** — file: `source/src/main/java/com/polestar/mti/dto/MetaDto.java`

Create class `MetaDto` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor`. Declare field `String requestId` annotated with `@JsonProperty("request_id")`. Declare field `String requestTimestamp` annotated with `@JsonProperty("request_timestamp")`. The `requestTimestamp` value should be an ISO 8601 string (formatted with `DateTimeFormatter.ISO_INSTANT` from `java.time.Instant`) — callers pass it as a pre-formatted string to keep DTOs free of time-formatting logic. Provide a static factory method `MetaDto of(String requestId)` that creates a `MetaDto` with the given `requestId` and `Instant.now().toString()` as the timestamp.

**Task 4.2.c — Create ScoresDto** — file: `source/src/main/java/com/polestar/mti/dto/ScoresDto.java`

Create class `ScoresDto` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor`. Declare six fields all of type `BigDecimal` (nullable): `mtiScore` annotated `@JsonProperty("mti_score")`; `vesselScore` annotated `@JsonProperty("vessel_score")`; `reportingScore` annotated `@JsonProperty("reporting_score")`; `voyagesScore` annotated `@JsonProperty("voyages_score")`; `emissionsScore` annotated `@JsonProperty("emissions_score")`; `sanctionsScore` annotated `@JsonProperty("sanctions_score")`. Annotate the class with `@JsonInclude(JsonInclude.Include.ALWAYS)` so that null `BigDecimal` fields are serialized as JSON `null` rather than being omitted (the default `NON_NULL` behavior would omit them, violating the PRD requirement).

**Task 4.2.d — Create MetadataDto** — file: `source/src/main/java/com/polestar/mti/dto/MetadataDto.java`

Create class `MetadataDto` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor`. Declare field `String createdAt` annotated `@JsonProperty("created_at")` and field `String updatedAt` annotated `@JsonProperty("updated_at")`. Both are pre-formatted ISO 8601 UTC strings (callers invoke `offsetDateTime.toInstant().toString()` before constructing this DTO).

**Task 4.2.e — Create MtiScoreDataDto** — file: `source/src/main/java/com/polestar/mti/dto/MtiScoreDataDto.java`

Create class `MtiScoreDataDto` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor`. Declare fields: `String imoNumber` annotated `@JsonProperty("imo_number")`; `Integer year` (no annotation needed, already lowercase); `Integer month`; `ScoresDto scores`; `MetadataDto metadata`.

**Task 4.2.f — Create ErrorDataDto** — file: `source/src/main/java/com/polestar/mti/dto/ErrorDataDto.java`

Create class `ErrorDataDto` in package `com.polestar.mti.dto` annotated with `@Getter` and `@AllArgsConstructor`. Declare fields: `String errorCode` annotated `@JsonProperty("error_code")`; `String title`; `String message`. Provide a static factory method `ErrorDataDto from(ErrorCode errorCode, String message)` that constructs an instance using `errorCode.getCode()`, `errorCode.getTitle()`, and the provided `message`.

**Complexity:** S | **Dependencies:** Story 4.1

---

### Epic 5: Service Layer

**Goal:** Implement all business logic — parameter validation, query routing, and response mapping — in `MtiScoreService`.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 5.1: MtiScoreService

**As a** developer **I want** a service class encapsulating all query-routing and mapping logic **so that** the controller remains a thin adapter.

**Background for implementer:** The service must handle four input combinations: (1) no year, no month → call `findTopByImoNumberOrderByYearDescMonthDesc`; (2) year only → call `findTopByImoNumberAndYearOrderByMonthDesc`; (3) year + month → call `findByImoNumberAndYearAndMonth`; (4) month without year → throw `MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`. Input combination (4) is a domain rule, not a Bean Validation constraint, because the two parameters are interdependent — Bean Validation operates on individual parameters and cannot express cross-parameter rules at the controller level without a custom `@Constraint`. Keeping this check in the service avoids Spring MVC internals complexity and keeps the rule alongside the other domain logic.

**Acceptance Criteria:**
- [ ] `MtiScoreService.getScores(String imoNumber, Integer year, Integer month)` routes to the correct repository method
- [ ] Returns `MtiScoreDataDto` populated from the found `MtiScore` entity
- [ ] Throws `MtiException(ErrorCode.ERR_101)` when repository returns empty
- [ ] Throws `MtiException(ErrorCode.ERR_102)` when month is non-null and year is null
- [ ] Logger is declared and logs at INFO for the three query paths and at WARN when not found

**Tasks:**

**Task 5.1.a — Create MtiScoreService** — file: `source/src/main/java/com/polestar/mti/service/MtiScoreService.java`

Create class `MtiScoreService` in package `com.polestar.mti.service` annotated with `@Service`. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoreService.class)` using `org.slf4j.Logger` and `org.slf4j.LoggerFactory`. Inject `MtiScoreRepository mtiScoreRepository` via constructor injection (declare a `final` field and a `@RequiredArgsConstructor` Lombok annotation on the class, or write the constructor manually).

Implement the method `public MtiScoreDataDto getScores(String imoNumber, Integer year, Integer month)`:
- First check: if `month != null && year == null`, log at WARN `log.warn("Month specified without year imo={}", imoNumber)` and throw `new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`.
- Determine which repository method to call: if both `year` and `month` are non-null, log `log.info("Fetching scores imo={} year={} month={}", imoNumber, year, month)` and call `mtiScoreRepository.findByImoNumberAndYearAndMonth(imoNumber, year, month)`; else if `year` is non-null, log `log.info("Fetching latest scores imo={} year={}", imoNumber, year)` and call `mtiScoreRepository.findTopByImoNumberAndYearOrderByMonthDesc(imoNumber, year)`; else log `log.info("Fetching latest scores imo={}", imoNumber)` and call `mtiScoreRepository.findTopByImoNumberOrderByYearDescMonthDesc(imoNumber)`.
- On empty `Optional`, log `log.warn("No scores found imo={} year={} month={}", imoNumber, year, month)` and throw `new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`.
- On present `Optional`, map the `MtiScore` entity to `MtiScoreDataDto`: construct `ScoresDto` from the six `BigDecimal` score fields; construct `MetadataDto` from `entity.getCreatedAt().toInstant().toString()` and `entity.getUpdatedAt().toInstant().toString()`; construct and return `new MtiScoreDataDto(entity.getImoNumber(), entity.getYear(), entity.getMonth(), scoresDto, metadataDto)`.

**Complexity:** M | **Dependencies:** Stories 3.1, 4.2

---

### Epic 6: Request Infrastructure (Filters)

**Goal:** Inject a UUID request ID into the MDC and response headers for every request, and enforce per-API-key rate limiting.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 6.1: RequestIdFilter

**As a** developer **I want** a servlet filter that generates a UUID per request and stores it in the SLF4J MDC **so that** every log line is traceable by request ID.

**Background for implementer:** The MDC (Mapped Diagnostic Context) is a thread-local map managed by SLF4J. The Logback pattern `%X{requestId}` in `application.yml` reads from it. The filter must call `MDC.clear()` in a `finally` block to prevent MDC leakage across thread pool reuses (this is critical in embedded Tomcat where threads are reused). The generated UUID is also added to the `X-Request-Id` response header so clients can correlate log entries. The constant `RequestIdFilter.REQUEST_ID_ATTR` holds the MDC key string `"requestId"` and is referenced by the controller when building `MetaDto`.

**Acceptance Criteria:**
- [ ] `RequestIdFilter` registered as a bean with `@Component` and `@Order(1)`
- [ ] Every request gets a UUID stored in MDC under key `"requestId"` and in request attribute `RequestIdFilter.REQUEST_ID_ATTR`
- [ ] `X-Request-Id` response header is set on every response
- [ ] MDC is always cleared in `finally` block

**Tasks:**

**Task 6.1.a — Create RequestIdFilter** — file: `source/src/main/java/com/polestar/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` in package `com.polestar.mti.filter` implementing `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"`. Declare `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`. Implement `doFilter(ServletRequest request, ServletResponse response, FilterChain chain)`: cast `request` to `HttpServletRequest` and `response` to `HttpServletResponse`; generate `String requestId = UUID.randomUUID().toString()`; call `MDC.put(REQUEST_ID_ATTR, requestId)`; call `request.setAttribute(REQUEST_ID_ATTR, requestId)`; call `httpResponse.setHeader("X-Request-Id", requestId)`; wrap the `chain.doFilter(request, response)` call in a `try` block with a `finally` block that calls `MDC.clear()`. Log at DEBUG after setting up: `log.debug("Request started requestId={} method={} uri={}", requestId, httpRequest.getMethod(), httpRequest.getRequestURI())`.

**Complexity:** S | **Dependencies:** None

---

#### Story 6.2: Rate Limit Filter

**As a** developer **I want** a Bucket4j-based rate limit filter **so that** each API key is limited to 100 requests per minute.

**Background for implementer:** Bucket4j uses the token-bucket algorithm. Each `Bucket` is configured with a `Bandwidth` that refills 100 tokens every 60 seconds. Buckets are stored per API key in a `ConcurrentHashMap<String, Bucket>`. The `X-Api-Key` header is the rate-limit key; if absent, the key defaults to the string `"anonymous"`. When a request consumes a token (`bucket.tryConsume(1)`), the request proceeds; otherwise HTTP 429 Too Many Requests is returned immediately with a JSON body `{"meta": {...}, "data": {"error_code": "ERR_429", ...}}` — note this is not an `MtiException` flow (the filter writes the response directly). `RateLimitConfig` reads `app.rate-limit.requests-per-minute` from `application.yml` and exposes the configured `long` value as a bean so the filter is testable without Spring context.

**Acceptance Criteria:**
- [ ] `RateLimitConfig` reads `app.rate-limit.requests-per-minute` and exposes a `long requestsPerMinute` bean
- [ ] `RateLimitFilter` is `@Order(2)` (runs after `RequestIdFilter`)
- [ ] Requests exceeding the limit receive HTTP 429 with JSON body
- [ ] Requests within the limit proceed to the next filter in the chain

**Tasks:**

**Task 6.2.a — Create RateLimitConfig** — file: `source/src/main/java/com/polestar/mti/config/RateLimitConfig.java`

Create class `RateLimitConfig` in package `com.polestar.mti.config` annotated with `@Configuration`. Declare field `long requestsPerMinute` annotated with `@Value("${app.rate-limit.requests-per-minute:100}")`. Expose a `@Bean` method `rateLimitRequestsPerMinute()` returning the `requestsPerMinute` value as a `Long`. This bean is injected into `RateLimitFilter` so the configured value can be verified in unit tests.

**Task 6.2.b — Create RateLimitFilter** — file: `source/src/main/java/com/polestar/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` in package `com.polestar.mti.filter` implementing `jakarta.servlet.Filter`, annotated with `@Component` and `@Order(2)`. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`. Declare `private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>()`. Inject `Long rateLimitRequestsPerMinute` via constructor from `RateLimitConfig`. Implement private method `Bucket newBucket()` that returns `Bucket.builder().addLimit(Bandwidth.classic(rateLimitRequestsPerMinute, Refill.greedy(rateLimitRequestsPerMinute, Duration.ofMinutes(1)))).build()` using `io.github.bucket4j.Bucket`, `io.github.bucket4j.Bandwidth`, and `io.github.bucket4j.Refill`. Implement `doFilter`: extract `String apiKey = httpRequest.getHeader("X-Api-Key")`, default to `"anonymous"` if blank; call `buckets.computeIfAbsent(apiKey, k -> newBucket())`; call `bucket.tryConsume(1)`; if true, call `chain.doFilter`; if false, log `log.warn("Rate limit exceeded apiKey={} uri={}", apiKey, httpRequest.getRequestURI())`, set response status 429, set `Content-Type: application/json`, and write a JSON body using `response.getWriter()` with the literal string `{"meta":{"request_id":"<requestId>","request_timestamp":"<now>"},"data":{"error_code":"ERR_429","title":"Too Many Requests","message":"Rate limit exceeded. Maximum 100 requests per minute."}}` where `<requestId>` is read from `request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` and `<now>` from `Instant.now().toString()`.

**Complexity:** M | **Dependencies:** Stories 4.1, 6.1

---

### Epic 7: Controller Layer

**Goal:** Expose `GET /api/v1/vessels/{imo}/mti-scores` with Bean Validation on path and query parameters, delegate to `MtiScoreService`, and wrap the result in `ApiResponse`.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 7.1: MtiScoreController

**As a** API consumer **I want** the `GET /api/v1/vessels/{imo}/mti-scores` endpoint **so that** I can retrieve MTI scores for a vessel.

**Background for implementer:** Spring MVC does not validate `@PathVariable` and `@RequestParam` constraints by default unless the controller class itself is annotated with `@Validated` (from `org.springframework.validation.annotation`). Without `@Validated`, `@Pattern`, `@Min`, and `@Max` annotations on method parameters are silently ignored. The `@Validated` annotation on the class triggers Bean Validation for method-level constraints. When a constraint is violated, Spring throws `ConstraintViolationException`, which `GlobalExceptionHandler` catches and maps to the appropriate `ErrorCode`.

**Acceptance Criteria:**
- [ ] `@GetMapping("/api/v1/vessels/{imo}/mti-scores")` is present
- [ ] IMO path variable is validated with `@Pattern(regexp = "^[0-9]{7}$")`
- [ ] `year` query param is validated with `@Min(2000)` and `@Max(2100)`
- [ ] `month` query param is validated with `@Min(1)` and `@Max(12)`
- [ ] Response is `ResponseEntity<ApiResponse<MtiScoreDataDto>>` with HTTP 200 on success
- [ ] `MetaDto.requestId` is populated from `RequestIdFilter.REQUEST_ID_ATTR` request attribute
- [ ] Logger logs the received request at INFO

**Tasks:**

**Task 7.1.a — Create MtiScoreController** — file: `source/src/main/java/com/polestar/mti/controller/MtiScoreController.java`

Create class `MtiScoreController` in package `com.polestar.mti.controller` annotated with `@RestController` and `@Validated`. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoreController.class)`. Inject `MtiScoreService mtiScoreService` via constructor. Implement method `ResponseEntity<ApiResponse<MtiScoreDataDto>> getMtiScores(...)` annotated with `@GetMapping("/api/v1/vessels/{imo}/mti-scores")`. The method parameters are: `@PathVariable @Pattern(regexp = "^[0-9]{7}$", message = "IMO number must be exactly 7 digits") String imo`; `@RequestParam(required = false) @Min(value = 2000, message = "Year must be >= 2000") @Max(value = 2100, message = "Year must be <= 2100") Integer year`; `@RequestParam(required = false) @Min(value = 1, message = "Month must be >= 1") @Max(value = 12, message = "Month must be <= 12") Integer month`; `HttpServletRequest request`. In the method body: extract `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if `requestId` is null default to `UUID.randomUUID().toString()`; log `log.info("getMtiScores imo={} year={} month={} requestId={}", imo, year, month, requestId)`; call `MtiScoreDataDto data = mtiScoreService.getScores(imo, year, month)`; construct `MetaDto meta = MetaDto.of(requestId)`; return `ResponseEntity.ok(new ApiResponse<>(meta, data))`.

**Complexity:** M | **Dependencies:** Stories 5.1, 6.1, 4.2

---

### Epic 8: Exception Handling

**Goal:** Provide a single `@RestControllerAdvice` that maps all exception types to correctly structured `ApiResponse<ErrorDataDto>` JSON responses.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 8.1: GlobalExceptionHandler

**As a** developer **I want** a centralized exception handler **so that** all errors — domain, validation, and unexpected — produce the standard `{"meta": {...}, "data": {"error_code": ...}}` envelope.

**Background for implementer:** Three distinct exception types must be handled: (1) `MtiException` — domain errors thrown by `MtiScoreService`; (2) `ConstraintViolationException` — thrown by Hibernate Validator when a `@PathVariable` or `@RequestParam` constraint fails; (3) `Exception` — catch-all for unexpected failures that maps to ERR_105. `ConstraintViolationException` constraint messages must be inspected to determine whether to return ERR_103 (IMO format) or ERR_104 (date range). The handler must also read the `requestId` from the MDC (not from the request, since it may not always be set) using `MDC.get(RequestIdFilter.REQUEST_ID_ATTR)`.

**Acceptance Criteria:**
- [ ] `MtiException` → `ApiResponse<ErrorDataDto>` with the exception's `ErrorCode` HTTP status
- [ ] `ConstraintViolationException` for IMO → HTTP 400 with ERR_103
- [ ] `ConstraintViolationException` for year/month → HTTP 400 with ERR_104
- [ ] Unhandled `Exception` → HTTP 500 with ERR_105
- [ ] All error responses include a valid `meta.request_id` from MDC
- [ ] Logger logs all exceptions at appropriate level

**Tasks:**

**Task 8.1.a — Create GlobalExceptionHandler** — file: `source/src/main/java/com/polestar/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in package `com.polestar.mti.exception` annotated with `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`. Implement private helper `MetaDto buildMeta()` that reads `String requestId = MDC.get(RequestIdFilter.REQUEST_ID_ATTR)`, defaults to `UUID.randomUUID().toString()` if null, and returns `MetaDto.of(requestId)`.

Implement `@ExceptionHandler(MtiException.class) ResponseEntity<ApiResponse<ErrorDataDto>> handleMtiException(MtiException ex)`: log at WARN `log.warn("MtiException errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage())`; construct `ErrorDataDto data = ErrorDataDto.from(ex.getErrorCode(), ex.getMessage())`; return `ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(new ApiResponse<>(buildMeta(), data))`.

Implement `@ExceptionHandler(ConstraintViolationException.class) ResponseEntity<ApiResponse<ErrorDataDto>> handleConstraintViolation(ConstraintViolationException ex)`: log at WARN `log.warn("Validation failed violations={}", ex.getMessage())`; inspect the constraint violation messages by iterating `ex.getConstraintViolations()` and checking if any `violation.getPropertyPath().toString()` contains `"imo"` — if so, use `ErrorCode.ERR_103` with message `"IMO number must be exactly 7 digits"`; otherwise use `ErrorCode.ERR_104` with message from the first violation's `getMessage()`; return HTTP 400.

Implement `@ExceptionHandler(Exception.class) ResponseEntity<ApiResponse<ErrorDataDto>> handleGeneral(Exception ex)`: log at ERROR `log.error("Unexpected error", ex)`; construct `ErrorDataDto data = ErrorDataDto.from(ErrorCode.ERR_105, "An unexpected error occurred")`; return HTTP 500.

**Complexity:** M | **Dependencies:** Stories 4.1, 4.2, 6.1

---

### Epic 9: Testing

**Goal:** Achieve ≥80% code coverage with unit tests for service logic and integration tests for the full request/response cycle.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 9.1: MtiScoreService Unit Tests

**As a** developer **I want** unit tests for `MtiScoreService` **so that** all business logic branches are verified in isolation.

**Acceptance Criteria:**
- [ ] All 4 routing branches tested (latest, year only, year+month, month-without-year)
- [ ] ERR_101 thrown when repository returns empty
- [ ] ERR_102 thrown when month is set but year is null
- [ ] Score fields correctly mapped from entity to DTO including null values
- [ ] Tests use Mockito to mock `MtiScoreRepository`

**Tasks:**

**Task 9.1.a — Create MtiScoreServiceTest** — file: `source/src/test/java/com/polestar/mti/service/MtiScoreServiceTest.java`

Create class `MtiScoreServiceTest` in package `com.polestar.mti.service` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoreRepository mtiScoreRepository` and `@InjectMocks MtiScoreService mtiScoreService`.

Implement helper `MtiScore buildEntity()` that returns an `MtiScore` with `imoNumber="9123456"`, `year=2024`, `month=1`, `mtiScore=new BigDecimal("85.50")`, `vesselScore=new BigDecimal("90.00")`, `reportingScore=new BigDecimal("88.75")`, `voyagesScore=new BigDecimal("82.30")`, `emissionsScore=new BigDecimal("87.60")`, `sanctionsScore=new BigDecimal("100.00")`, `createdAt=OffsetDateTime.parse("2024-01-01T00:00:00Z")`, `updatedAt=OffsetDateTime.parse("2024-01-01T00:00:00Z")`.

Test 1 — `getScores_latest_returnsDto`: call `getScores("9123456", null, null)`, stub `findTopByImoNumberOrderByYearDescMonthDesc("9123456")` to return `Optional.of(buildEntity())`, assert returned `MtiScoreDataDto.getImoNumber()` equals `"9123456"`, `getYear()` equals `2024`, `getMonth()` equals `1`, `getScores().getMtiScore()` equals `new BigDecimal("85.50")`.

Test 2 — `getScores_yearOnly_returnsDto`: call `getScores("9123456", 2023, null)`, stub `findTopByImoNumberAndYearOrderByMonthDesc("9123456", 2023)` to return `Optional.of(buildEntity())`, assert `imoNumber` equals `"9123456"`.

Test 3 — `getScores_yearAndMonth_returnsDto`: call `getScores("9123456", 2023, 6)`, stub `findByImoNumberAndYearAndMonth("9123456", 2023, 6)` to return `Optional.of(buildEntity())`, assert `imoNumber` equals `"9123456"`.

Test 4 — `getScores_monthWithoutYear_throwsErr102`: call `getScores("9123456", null, 6)`, assert throws `MtiException` with `getErrorCode()` equal to `ErrorCode.ERR_102`.

Test 5 — `getScores_notFound_throwsErr101`: stub `findTopByImoNumberOrderByYearDescMonthDesc("9999999")` to return `Optional.empty()`, call `getScores("9999999", null, null)`, assert throws `MtiException` with `getErrorCode()` equal to `ErrorCode.ERR_101`.

Test 6 — `getScores_nullScores_returnedAsNull`: build entity with `mtiScore=null`, stub latest query, assert `getScores().getMtiScore()` is `null`.

**Complexity:** M | **Dependencies:** Story 5.1

---

#### Story 9.2: Controller Integration Tests

**As a** developer **I want** integration tests using Testcontainers **so that** the full HTTP request/response cycle is verified against a real PostgreSQL database.

**Background for implementer:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` starts the full application context. The Testcontainers `@Container` annotation creates a PostgreSQL 15 container and `@DynamicPropertySource` injects `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` so the application connects to the test container instead of any real DB. Flyway runs automatically on context startup and creates the `mti_scores_history` table. A `JdbcTemplate` is injected to insert seed data before each test. `TestRestTemplate` is used (not `MockMvc`) because filters (`RequestIdFilter`, `RateLimitFilter`) must execute in the full servlet chain.

**Acceptance Criteria:**
- [ ] AC1: GET `/api/v1/vessels/9123456/mti-scores` returns 200 with correct scores
- [ ] AC3: GET `/api/v1/vessels/9123456/mti-scores?year=2023&month=6` returns 200
- [ ] AC4: GET `/api/v1/vessels/9999999/mti-scores` returns 404 with `error_code="ERR_101"`
- [ ] AC5: GET `/api/v1/vessels/123/mti-scores` returns 400 with `error_code="ERR_103"`
- [ ] AC6: GET `/api/v1/vessels/9123456/mti-scores?month=6` returns 400 with `error_code="ERR_102"`
- [ ] AC7: GET `/api/v1/vessels/9123456/mti-scores?year=2023&month=13` returns 400 with `error_code="ERR_104"`
- [ ] `X-Request-Id` response header is present on all responses
- [ ] `meta.request_id` matches `X-Request-Id` header value

**Tasks:**

**Task 9.2.a — Create application-test.yml** — file: `source/src/test/resources/application-test.yml`

Create test application configuration overriding `app.rate-limit.requests-per-minute` to `1000` (to avoid rate limiting interference in tests) and `logging.level.com.polestar.mti` to `DEBUG`. No datasource overrides are needed here — `@DynamicPropertySource` handles them at runtime.

**Task 9.2.b — Create MtiScoreControllerIntegrationTest** — file: `source/src/test/java/com/polestar/mti/controller/MtiScoreControllerIntegrationTest.java`

Create class `MtiScoreControllerIntegrationTest` in package `com.polestar.mti.controller` annotated with `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` and `@ActiveProfiles("test")`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Annotate a `@DynamicPropertySource static void configureProperties(DynamicPropertyRegistry registry)` method that registers `spring.datasource.url` from `postgres.getJdbcUrl()`, `spring.datasource.username` from `postgres.getUsername()`, `spring.datasource.password` from `postgres.getPassword()`. Inject `@Autowired TestRestTemplate restTemplate`, `@Autowired JdbcTemplate jdbcTemplate`, and `@LocalServerPort int port`.

Implement `@BeforeEach void setUp()` that calls `jdbcTemplate.execute("DELETE FROM mti_scores_history")` then inserts two rows: row 1 — `imoNumber="9123456"`, `year=2024`, `month=1`, `mtiScore=85.50`, `vesselScore=90.00`, `reportingScore=88.75`, `voyagesScore=82.30`, `emissionsScore=87.60`, `sanctionsScore=100.00`, using `jdbcTemplate.update("INSERT INTO mti_scores_history (imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())", "9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00)`; row 2 — same IMO with `year=2023`, `month=6`, all score values `75.00`.

Test `getLatestScores_returns200`: GET `/api/v1/vessels/9123456/mti-scores`, assert status 200, `body.data.imo_number` equals `"9123456"`, `body.data.year` equals `2024`, `body.data.scores.mti_score` equals `85.50`, `body.meta.request_id` is not null, response header `X-Request-Id` equals `body.meta.request_id`.

Test `getScoresByYearAndMonth_returns200`: GET `/api/v1/vessels/9123456/mti-scores?year=2023&month=6`, assert status 200, `body.data.year` equals `2023`, `body.data.month` equals `6`.

Test `unknownImo_returns404`: GET `/api/v1/vessels/9999999/mti-scores`, assert status 404, `body.data.error_code` equals `"ERR_101"`.

Test `invalidImoFormat_returns400Err103`: GET `/api/v1/vessels/123/mti-scores`, assert status 400, `body.data.error_code` equals `"ERR_103"`.

Test `monthWithoutYear_returns400Err102`: GET `/api/v1/vessels/9123456/mti-scores?month=6`, assert status 400, `body.data.error_code` equals `"ERR_102"`.

Test `invalidMonth_returns400Err104`: GET `/api/v1/vessels/9123456/mti-scores?year=2023&month=13`, assert status 400, `body.data.error_code` equals `"ERR_104"`.

Use `restTemplate.getForEntity` with `String.class` and then parse with `ObjectMapper`, or declare typed response wrappers using `ParameterizedTypeReference<ApiResponse<MtiScoreDataDto>>` and `ParameterizedTypeReference<ApiResponse<ErrorDataDto>>` for type-safe assertions.

**Complexity:** L | **Dependencies:** Stories 7.1, 8.1, 9.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo   String   Required   Must match ^[0-9]{7}$ (exactly 7 digits)

Query Parameters:
  year    Integer   Optional   2000–2100 inclusive
  month   Integer   Optional   1–12 inclusive; requires year to be present

Request Headers:
  X-Api-Key: used as rate-limit bucket key; defaults to "anonymous" if absent

Success Response — 200 OK:
  meta.request_id           String (UUID)    Unique identifier for this request
  meta.request_timestamp    String (ISO 8601 UTC)   Time the request was processed
  data.imo_number           String           7-digit IMO number
  data.year                 Integer          Year of the returned scores
  data.month                Integer          Month of the returned scores
  data.scores.mti_score     Number | null    MTI composite score
  data.scores.vessel_score  Number | null
  data.scores.reporting_score Number | null
  data.scores.voyages_score   Number | null
  data.scores.emissions_score Number | null
  data.scores.sanctions_score Number | null
  data.metadata.created_at  String (ISO 8601 UTC)
  data.metadata.updated_at  String (ISO 8601 UTC)

Error Response — 400 / 404 / 500:
  meta.request_id           String (UUID)
  meta.request_timestamp    String (ISO 8601 UTC)
  data.error_code           String           ERR_101..ERR_105
  data.title                String           Human-readable error category
  data.message              String           Specific detail about this occurrence

Error Code Reference:
  ERR_101   404   No scores found for the given IMO / year / month combination
  ERR_102   400   Month parameter specified without year
  ERR_103   400   IMO path parameter does not match ^[0-9]{7}$
  ERR_104   400   year out of [2000,2100] or month out of [1,12]
  ERR_105   500   Unexpected server error (database, runtime)
  ERR_429   429   Rate limit exceeded (100 req/min per X-Api-Key)
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 100ms under normal load; composite index on `(imo_number, year DESC, month DESC)` ensures single-scan queries |
| Logging | Logback; MDC field `requestId` embedded in every line via pattern `%X{requestId}`; DEBUG for filter entry, INFO for service query, WARN for not-found and rate-limit, ERROR for unexpected exceptions |
| Metrics | Spring Boot Actuator (`/actuator/health`) exposed; no external metrics system in scope |
| Security | Input validated at controller via Bean Validation; no SQL injection risk (Spring Data JPA parameterizes all queries); no sensitive data in error messages (entity IDs not exposed) |
| Rate Limiting | Token-bucket via Bucket4j 8.10; 100 tokens/minute per `X-Api-Key`; in-memory `ConcurrentHashMap`; HTTP 429 response with JSON error body |
| Testing | ≥80% line coverage; unit tests with Mockito; integration tests with Testcontainers PostgreSQL 15 |
| Health / Docs | `GET /actuator/health` returns `{"status":"UP"}`; OpenAPI spec in PRD `docs/prd.md` section 6 |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `MtiScoreController` | `MtiScoreService` | Delegates all logic to service |
| `MtiScoreController` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request ID from attribute set by filter |
| `MtiScoreController` | `MetaDto`, `ApiResponse`, `MtiScoreDataDto` | Constructs response envelope |
| `MtiScoreService` | `MtiScoreRepository` | Executes all DB queries |
| `MtiScoreService` | `ErrorCode.ERR_101`, `ErrorCode.ERR_102` | Throws typed domain exceptions |
| `MtiScoreService` | `MtiScoreDataDto`, `ScoresDto`, `MetadataDto` | Maps entity to response DTO |
| `GlobalExceptionHandler` | `ErrorCode` (all five) | Maps exception types to HTTP statuses and codes |
| `GlobalExceptionHandler` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads requestId from MDC for error responses |
| `GlobalExceptionHandler` | `ApiResponse`, `ErrorDataDto`, `MetaDto` | Constructs error response envelope |
| `RateLimitFilter` | `RateLimitConfig` | Reads `requestsPerMinute` value |
| `RateLimitFilter` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads requestId to include in 429 response body |
| `MtiScore` entity | `mti_scores_history` table columns | JPA mapping; all column names must match exactly |
| `MtiScoreRepository` | `MtiScore` entity | Spring Data JPA derives queries from entity field names |
| `RequestIdFilter` | `MDC` (SLF4J) | Stores `requestId` in thread-local diagnostic context |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — `pom.xml` must exist before any Java compiles
2. **Story 1.2** — application entry point and `application.yml` needed before Spring context starts
3. **Story 2.1** — Flyway migration must exist before JPA entity validation runs
4. **Story 2.2** — JPA entity needed before repository can reference it
5. **Story 4.1** — `ErrorCode` and `MtiException` are zero-dependency; unblock service and handler
6. **Story 3.1** — Repository needs the entity; service needs the repository
7. **Story 4.2** — DTOs needed before service can return typed results
8. **Story 5.1** — Service needs repository, DTOs, and constants
9. **Story 6.1** — `RequestIdFilter` has no dependencies; can be done in parallel with Story 4.x
10. **Story 6.2** — Rate limit filter depends on Story 6.1 (reads `REQUEST_ID_ATTR`)
11. **Story 7.1** — Controller needs service, filter constant, and DTOs
12. **Story 8.1** — Exception handler needs all error constants and DTOs
13. **Story 9.1** — Unit tests need the service
14. **Story 9.2** — Integration tests need the full application stack

> Stories 4.1, 4.2, and 6.1 can be developed in parallel since they have no interdependencies. Stories 9.1 and 9.2 can be developed in parallel once the application stack (Stories 1–8) is complete.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `ApiResponse<T>` | `MetaDto meta`, `T data` | `meta`, `data` | Generic envelope used for both success and error |
| `MetaDto` | `String requestId`, `String requestTimestamp` | `request_id`, `request_timestamp` | UUID and ISO 8601 UTC string |
| `MtiScoreDataDto` | `String imoNumber`, `Integer year`, `Integer month`, `ScoresDto scores`, `MetadataDto metadata` | `imo_number`, `year`, `month`, `scores`, `metadata` | Success data payload |
| `ScoresDto` | `BigDecimal mtiScore`, `vesselScore`, `reportingScore`, `voyagesScore`, `emissionsScore`, `sanctionsScore` | `mti_score`, `vessel_score`, `reporting_score`, `voyages_score`, `emissions_score`, `sanctions_score` | All fields nullable; `@JsonInclude(ALWAYS)` required |
| `MetadataDto` | `String createdAt`, `String updatedAt` | `created_at`, `updated_at` | Pre-formatted ISO 8601 strings |
| `ErrorDataDto` | `String errorCode`, `String title`, `String message` | `error_code`, `title`, `message` | Error data payload |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | Yes | `jdbc:postgresql://localhost:5432/mtidb` | Full JDBC URL for PostgreSQL connection |
| `DATABASE_USERNAME` | Yes | `mti` | PostgreSQL username |
| `DATABASE_PASSWORD` | Yes | `mti` | PostgreSQL password |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | |
| `year` | INTEGER | No | |
| `month` | INTEGER | No | |
| `mti_score` | NUMERIC(10,2) | Yes | |
| `vessel_score` | NUMERIC(10,2) | Yes | |
| `reporting_score` | NUMERIC(10,2) | Yes | |
| `voyages_score` | NUMERIC(10,2) | Yes | |
| `emissions_score` | NUMERIC(10,2) | Yes | |
| `sanctions_score` | NUMERIC(10,2) | Yes | |
| `created_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |

Additional constraints:
- Unique index `idx_mti_scores_imo_year_month_unique` on `(imo_number, year, month)` — prevents duplicate records
- Composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` — optimizes all three query patterns

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| In-memory rate limit state lost on restart | H | L | Acceptable for single-instance; document as known limitation; upgrade to Redis-backed Bucket4j for multi-instance deployments |
| Testcontainers unavailable in CI environment | M | M | Ensure Docker socket is mounted in CI; use `@TestcontainersIntegrationTest` profile guard |
| NULL score fields omitted in JSON response | M | H | `@JsonInclude(JsonInclude.Include.ALWAYS)` on `ScoresDto` prevents this; verified in integration test |
| IMO validation bypassed by URL encoding | L | M | Spring MVC decodes path variables before Bean Validation applies; the regex `^[0-9]{7}$` will still reject non-digit characters |
| JPA `ddl-auto=validate` fails on schema drift | M | H | Keep entity field names strictly in sync with migration column names; add migration for any schema change |

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
- [ ] API documentation updated (OpenAPI spec in `docs/prd.md` section 6 matches implementation)
- [ ] Docker image builds and runs locally (`docker-compose up` starts successfully)
- [ ] Environment variables documented in `README.md`
- [ ] Flyway migration history clean (no repair needed)

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven POM is the prerequisite for everything; no code compiles without it
2. **Story 1.2** — Application entry point and configuration; needed before Spring context
3. **Story 2.1** — Flyway migration; JPA validation (`ddl-auto=validate`) requires table to exist
4. **Story 2.2** — JPA entity; repository cannot be defined without it
5. **Story 4.1** — `ErrorCode` and `MtiException`; zero dependencies, unlocks service and handler work
6. **Story 3.1** — Repository; depends on entity (Story 2.2)
7. **Story 4.2** — Response DTOs; depends on `ErrorCode` (Story 4.1)
8. **Story 5.1** — Service; depends on repository (3.1) and DTOs (4.2)
9. **Story 6.1** — `RequestIdFilter`; zero dependencies, can be parallelized with Stories 4.x
10. **Story 6.2** — Rate limit filter; depends on `RequestIdFilter.REQUEST_ID_ATTR` (Story 6.1)
11. **Story 7.1** — Controller; depends on service (5.1), filter constant (6.1), and DTOs (4.2)
12. **Story 8.1** — Exception handler; depends on all constants and DTOs (4.1, 4.2, 6.1)
13. **Story 9.1** — Service unit tests; depends on service (5.1)
14. **Story 9.2** — Controller integration tests; depends on full stack (Stories 1–8)

> Stories 4.1, 4.2, and 6.1 can be developed in parallel (no interdependencies). Once the full stack (Stories 1–8) is complete, Stories 9.1 and 9.2 can be developed in parallel.
