# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

The MTI Scores API provides a single REST endpoint (`GET /api/v1/vessels/{imo}/mti-scores`) that retrieves Maritime Transportation Indicator scores for vessels identified by their 7-digit IMO number. The API supports optional filtering by year and month, returning the most recent available scores when no filters are specified. The implementation uses a Java 17 Spring Boot 3.x stack with PostgreSQL, Flyway migrations, Bucket4j rate limiting, and comprehensive input validation enforcing five business error codes (ERR_101 through ERR_105). All requests are stamped with a UUID v4 request_id propagated via a servlet filter into both the JSON response and SLF4J MDC for end-to-end traceability.

---

## TECHNICAL ANALYSIS

### Recommended Stack (Greenfield)

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 17 | LTS release, widely supported, strong typing for domain validation |
| Build | Maven 3.9 | Standard enterprise build tool with mature dependency management |
| Framework | Spring Boot 3.2.x | Auto-configuration reduces boilerplate; built-in validation, web, and JDBC support |
| Web | Spring MVC (embedded Tomcat) | Synchronous REST fits the simple query pattern; no reactive complexity needed |
| Database | PostgreSQL 15 | Production-grade relational DB with native NUMERIC type for score precision |
| DB Access | Spring JdbcTemplate | Lightweight; avoids ORM overhead for three fixed read-only queries |
| Migrations | Flyway 10.x | Versioned SQL migrations, integrated with Spring Boot auto-configuration |
| Rate Limiting | Bucket4j 8.10.x (in-process) | Token-bucket algorithm, zero external dependency; swap to Redis-backed in production cluster |
| API Docs | SpringDoc OpenAPI 2.x | Auto-generates Swagger UI from Spring MVC annotations |
| Testing | JUnit 5 + Mockito + Testcontainers | Unit isolation + real PostgreSQL for integration tests |

### Project Structure

```
mti-scores-api/
├── src/
│   ├── main/
│   │   ├── java/com/example/mti/
│   │   │   ├── MtiApplication.java
│   │   │   ├── config/
│   │   │   │   ├── OpenApiConfig.java
│   │   │   │   └── RateLimitConfig.java
│   │   │   ├── constant/
│   │   │   │   └── ErrorCode.java
│   │   │   ├── controller/
│   │   │   │   └── MtiScoreController.java
│   │   │   ├── dto/
│   │   │   │   ├── ErrorDataDto.java
│   │   │   │   ├── ErrorResponseDto.java
│   │   │   │   ├── MetaDto.java
│   │   │   │   ├── ScoreDataDto.java
│   │   │   │   ├── ScoreMetadataDto.java
│   │   │   │   ├── ScoresDto.java
│   │   │   │   └── SuccessResponseDto.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── InvalidParameterException.java
│   │   │   │   ├── MtiException.java
│   │   │   │   └── ResourceNotFoundException.java
│   │   │   ├── filter/
│   │   │   │   ├── RateLimitFilter.java
│   │   │   │   └── RequestIdFilter.java
│   │   │   ├── model/
│   │   │   │   └── MtiScoreRecord.java
│   │   │   ├── repository/
│   │   │   │   └── MtiScoreRepository.java
│   │   │   └── service/
│   │   │       └── MtiScoreService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/
│   │           └── migration/
│   │               ├── V1__create_mti_scores_history.sql
│   │               └── V2__seed_test_data.sql
│   └── test/
│       ├── java/com/example/mti/
│       │   ├── controller/
│       │   │   └── MtiScoreControllerTest.java
│       │   ├── integration/
│       │   │   └── MtiScoreIntegrationTest.java
│       │   └── service/
│       │       └── MtiScoreServiceTest.java
│       └── resources/
│           └── application-test.yml
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

### Integration Points

- **PostgreSQL database**: Single table `mti_scores_history` queried via three parameterized SQL queries using JdbcTemplate
- **No external HTTP APIs**: The service is purely a read API over a pre-populated database

### Technical Constraints

- **Performance**: p95 latency < 100ms for database queries; composite index `(imo_number, year DESC, month DESC)` mandatory
- **Security**: Parameterized queries only (no string concatenation); no sensitive data in error responses
- **Rate Limiting**: 100 requests/minute per client IP; in-process (single-node); production should move to Redis-backed Bucket4j
- **Validation**: IMO must match `^[0-9]{7}$`; year range 2000–2100; month range 1–12; month requires year

---

## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.mti` | **Group ID:** `com.example` | **Artifact ID:** `mti-scores-api`

### Overview

The backend consists of a single Spring Boot application exposing one REST endpoint. The implementation layers are: a servlet filter for request ID injection, a rate-limit filter for IP-based throttling, a validation-and-routing service, a JdbcTemplate-based repository, and a global exception handler. All layers log using SLF4J with the request_id in MDC for end-to-end traceability.

---

### Epic 1: Project Scaffolding

**Goal:** Establish the buildable Maven project with all runtime and test dependencies, database migrations, and Spring Boot entry point.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 1.1: Maven Project Bootstrap

**As a** developer **I want** a buildable Maven project with all required dependencies **so that** subsequent stories compile and run without dependency conflicts.

**Acceptance Criteria:**
- [ ] `pom.xml` declares Java 17 source/target, Spring Boot parent 3.2.5, and all required dependencies
- [ ] `mvn clean package -DskipTests` succeeds
- [ ] `MtiApplication.java` starts the Spring Boot context

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `mti-scores-api/pom.xml`

Create the Maven project descriptor with groupId `com.example`, artifactId `mti-scores-api`, version `0.0.1-SNAPSHOT`, and parent `org.springframework.boot:spring-boot-starter-parent:3.2.5`. Set Java source/target to 17 via `maven.compiler.source` and `maven.compiler.target` properties. Add the following dependencies: `org.springframework.boot:spring-boot-starter-web` (web layer), `org.springframework.boot:spring-boot-starter-jdbc` (JdbcTemplate), `org.springframework.boot:spring-boot-starter-validation` (Bean Validation), `org.postgresql:postgresql:42.7.3` (runtime scope), `org.flywaydb:flyway-core:10.12.0` (migrations), `org.flywaydb:flyway-database-postgresql:10.12.0` (Flyway PostgreSQL support), `com.bucket4j:bucket4j-core:8.10.1` (rate limiting), `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0` (API docs), `org.springframework.boot:spring-boot-starter-test` (test scope), `org.testcontainers:junit-jupiter:1.19.8` (test scope), `org.testcontainers:postgresql:1.19.8` (test scope). Add the `spring-boot-maven-plugin` in the build section for executable JAR packaging.

**Task 1.1.b — Create MtiApplication.java** — file: `mti-scores-api/src/main/java/com/example/mti/MtiApplication.java`

Create class `MtiApplication` annotated with `@SpringBootApplication`. Implement a `public static void main(String[] args)` method that calls `SpringApplication.run(MtiApplication.class, args)`. This is the standard Spring Boot entry point; no additional configuration is needed here.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Database Migration

**As a** developer **I want** Flyway migrations to create and seed the `mti_scores_history` table **so that** the repository can query real data.

**Background for implementer:** Flyway runs automatically on startup via Spring Boot auto-configuration when `flyway-core` and `flyway-database-postgresql` are on the classpath. Migration files must be placed in `src/main/resources/db/migration/` and named `V{version}__{description}.sql`. The seed data migration (`V2`) is included for local development and integration testing. In a production environment this migration should be omitted or guarded by a profile-based `spring.flyway.locations` override.

**Acceptance Criteria:**
- [ ] `mti_scores_history` table is created with all required columns and the composite index
- [ ] Four seed rows are inserted for IMO `9123456` (years 2022–2024) and zero rows for IMO `9999999`
- [ ] One seed row for IMO `9123456` year 2022 has NULL values in `vessel_score` and `voyages_score` to support AC8 testing

**Tasks:**

**Task 1.2.a — Create V1 migration** — file: `mti-scores-api/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Write a SQL migration that creates the `mti_scores_history` table with the following columns: `id` as BIGSERIAL PRIMARY KEY, `imo_number` as VARCHAR(7) NOT NULL, `year` as INTEGER NOT NULL, `month` as INTEGER NOT NULL, `mti_score` as NUMERIC(5,2) NULL, `vessel_score` as NUMERIC(5,2) NULL, `reporting_score` as NUMERIC(5,2) NULL, `voyages_score` as NUMERIC(5,2) NULL, `emissions_score` as NUMERIC(5,2) NULL, `sanctions_score` as NUMERIC(5,2) NULL, `created_at` as TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), `updated_at` as TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(). After the CREATE TABLE statement, add a UNIQUE constraint named `uq_mti_scores_imo_year_month` on columns `(imo_number, year, month)`. Create a composite index named `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` to support the ordered queries described in the PRD database query logic section.

**Task 1.2.b — Create V2 seed migration** — file: `mti-scores-api/src/main/resources/db/migration/V2__seed_test_data.sql`

Write a SQL migration that inserts exactly four rows into `mti_scores_history`. Row 1: imo_number=`'9123456'`, year=`2024`, month=`1`, mti_score=`85.50`, vessel_score=`90.00`, reporting_score=`88.75`, voyages_score=`82.30`, emissions_score=`87.60`, sanctions_score=`100.00`, created_at=`'2024-01-01 00:00:00+00'`, updated_at=`'2024-01-01 00:00:00+00'`. Row 2: imo_number=`'9123456'`, year=`2023`, month=`12`, mti_score=`82.00`, vessel_score=`87.00`, reporting_score=`83.00`, voyages_score=`79.00`, emissions_score=`83.00`, sanctions_score=`97.00`, created_at=`'2023-12-01 00:00:00+00'`, updated_at=`'2023-12-01 00:00:00+00'`. Row 3: imo_number=`'9123456'`, year=`2023`, month=`6`, mti_score=`80.00`, vessel_score=`85.00`, reporting_score=`82.00`, voyages_score=`78.00`, emissions_score=`81.00`, sanctions_score=`95.00`, created_at=`'2023-06-01 00:00:00+00'`, updated_at=`'2023-06-01 00:00:00+00'`. Row 4: imo_number=`'9123456'`, year=`2022`, month=`3`, mti_score=`75.00`, vessel_score=`NULL`, reporting_score=`79.00`, voyages_score=`NULL`, emissions_score=`80.00`, sanctions_score=`90.00`, created_at=`'2022-03-01 00:00:00+00'`, updated_at=`'2022-03-01 00:00:00+00'`. Row 4's NULL vessel_score and voyages_score support AC8 (partial score data) testing.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 1.3: Application Configuration

**As a** developer **I want** a properly configured `application.yml` and a test profile **so that** the application connects to PostgreSQL in production and uses H2 in-memory during unit/slice tests.

**Acceptance Criteria:**
- [ ] `application.yml` externalizes all secrets via environment variables
- [ ] `application-test.yml` enables H2 in-memory mode for unit/slice tests and disables Flyway
- [ ] `app.rate-limit.*` properties are defined with correct default values

**Tasks:**

**Task 1.3.a — Create application.yml** — file: `mti-scores-api/src/main/resources/application.yml`

Create the main Spring Boot configuration file. Set `spring.application.name` to `mti-scores-api`. Configure `spring.datasource.url` to resolve from environment variable `DATABASE_URL` using Spring property placeholder syntax `${DATABASE_URL:jdbc:postgresql://localhost:5432/mti}`. Configure `spring.datasource.username` from `${DATABASE_USERNAME:mti}`. Configure `spring.datasource.password` from `${DATABASE_PASSWORD:mti}`. Set `spring.datasource.driver-class-name` to `org.postgresql.Driver`. Set `spring.flyway.enabled` to `true` and `spring.flyway.locations` to `classpath:db/migration`. Configure the `app.rate-limit` group: `app.rate-limit.capacity` to `100`, `app.rate-limit.refill-tokens` to `100`, `app.rate-limit.refill-period-seconds` to `60`. Set `springdoc.api-docs.path` to `/api-docs` and `springdoc.swagger-ui.path` to `/swagger-ui.html`. Set `logging.pattern.console` to `%d{ISO8601} [%X{requestId}] %-5level %logger{36} - %msg%n` to include the MDC requestId field in all log lines.

**Task 1.3.b — Create application-test.yml** — file: `mti-scores-api/src/test/resources/application-test.yml`

Create the test profile configuration. Set `spring.datasource.url` to `jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`. Set `spring.datasource.driver-class-name` to `org.h2.Driver`. Set `spring.flyway.enabled` to `false`. Set `app.rate-limit.capacity` to `1000` so rate limiting does not interfere with unit test execution. This profile is activated by annotating test classes with `@ActiveProfiles("test")`. Note: integration tests using Testcontainers override datasource properties via `@DynamicPropertySource` and re-enable Flyway.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Core Domain Types

**Goal:** Define all shared constants, DTOs, domain model, and exception hierarchy so that all upper layers compile against stable interfaces.
**Priority:** High | **Estimated Complexity:** S

---

#### Story 2.1: Error Code Constant

**As a** developer **I want** a single `ErrorCode` enum defining all five error codes and their HTTP statuses **so that** the service and exception handler share one source of truth.

**Acceptance Criteria:**
- [ ] `ErrorCode` enum has exactly five constants: `ERR_101`, `ERR_102`, `ERR_103`, `ERR_104`, `ERR_105`
- [ ] Each constant carries `httpStatus` (int), `title` (String), and `defaultMessage` (String)

**Tasks:**

**Task 2.1.a — Create ErrorCode enum** — file: `mti-scores-api/src/main/java/com/example/mti/constant/ErrorCode.java`

Create a Java enum `ErrorCode` in package `com.example.mti.constant`. Each constant must carry three fields set via a constructor: `int httpStatus`, `String title`, and `String defaultMessage`. Define the following five constants: `ERR_101` with httpStatus=`404`, title=`"Resource Not Found"`, defaultMessage=`"No MTI scores found for the given IMO number"`; `ERR_102` with httpStatus=`400`, title=`"Invalid Parameters"`, defaultMessage=`"Month parameter requires year parameter to be specified"`; `ERR_103` with httpStatus=`400`, title=`"Invalid IMO Format"`, defaultMessage=`"IMO number must be exactly 7 digits"`; `ERR_104` with httpStatus=`400`, title=`"Invalid Date Range"`, defaultMessage=`"Invalid year or month value"`; `ERR_105` with httpStatus=`500`, title=`"Internal Server Error"`, defaultMessage=`"An internal error occurred"`. Add getter methods `getHttpStatus()`, `getTitle()`, `getDefaultMessage()`, and `getCode()` where `getCode()` returns `this.name()` (i.e., the enum constant name as a String such as `"ERR_101"`).

**Complexity:** S | **Dependencies:** None

---

#### Story 2.2: Data Transfer Objects

**As a** developer **I want** immutable Java records for all request/response DTOs **so that** the controller, service, and exception handler exchange strongly-typed objects.

**Acceptance Criteria:**
- [ ] All DTOs are Java records (immutable)
- [ ] JSON field names match the PRD (snake_case) via `@JsonProperty` where record component names differ
- [ ] `ScoresDto` allows all six score fields to be `null` (boxed Double)

**Tasks:**

**Task 2.2.a — Create MetaDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/MetaDto.java`

Create a Java record `MetaDto` in package `com.example.mti.dto` with two String components: `requestId` and `requestTimestamp`. Annotate `requestId` with `@JsonProperty("request_id")` and `requestTimestamp` with `@JsonProperty("request_timestamp")`. This record is used in both success and error response wrappers.

**Task 2.2.b — Create ScoresDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/ScoresDto.java`

Create a Java record `ScoresDto` with six nullable `Double` components: `mtiScore`, `vesselScore`, `reportingScore`, `voyagesScore`, `emissionsScore`, `sanctionsScore`. Annotate each component with the matching `@JsonProperty` snake_case name: `"mti_score"`, `"vessel_score"`, `"reporting_score"`, `"voyages_score"`, `"emissions_score"`, `"sanctions_score"` respectively. All fields must be boxed `Double` (not primitive `double`) to allow `null` values, satisfying AC8.

**Task 2.2.c — Create ScoreMetadataDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/ScoreMetadataDto.java`

Create a Java record `ScoreMetadataDto` in package `com.example.mti.dto` with two String components: `createdAt` annotated `@JsonProperty("created_at")` and `updatedAt` annotated `@JsonProperty("updated_at")`. This type is used as the `metadata` field inside `ScoreDataDto` and holds ISO-8601 formatted timestamp strings derived from the `OffsetDateTime` fields of `MtiScoreRecord`.

**Task 2.2.d — Create ScoreDataDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/ScoreDataDto.java`

Create a Java record `ScoreDataDto` in package `com.example.mti.dto` with five components: `String imoNumber` annotated `@JsonProperty("imo_number")`, `Integer year`, `Integer month`, `ScoresDto scores`, and `ScoreMetadataDto metadata`. This record is used as the `data` field in `SuccessResponseDto`.

**Task 2.2.e — Create SuccessResponseDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/SuccessResponseDto.java`

Create a Java record `SuccessResponseDto` in package `com.example.mti.dto` with two components: `MetaDto meta` and `ScoreDataDto data`. This is the top-level response envelope returned by the controller on HTTP 200.

**Task 2.2.f — Create ErrorDataDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/ErrorDataDto.java`

Create a Java record `ErrorDataDto` in package `com.example.mti.dto` with three String components: `errorCode` annotated `@JsonProperty("error_code")`, `title`, and `message`. This represents the `data` object inside an error response.

**Task 2.2.g — Create ErrorResponseDto** — file: `mti-scores-api/src/main/java/com/example/mti/dto/ErrorResponseDto.java`

Create a Java record `ErrorResponseDto` in package `com.example.mti.dto` with two components: `MetaDto meta` and `ErrorDataDto data`. This is the top-level error response envelope returned by `GlobalExceptionHandler` for all 4XX and 5XX responses.

**Complexity:** S | **Dependencies:** Story 2.1

---

#### Story 2.3: Domain Model

**As a** developer **I want** a `MtiScoreRecord` POJO that maps directly to `mti_scores_history` columns **so that** the repository's `RowMapper` has a strongly-typed target.

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` has fields matching all 12 columns in `mti_scores_history`
- [ ] Score fields are boxed `Double` (nullable); timestamp fields are `OffsetDateTime`

**Tasks:**

**Task 2.3.a — Create MtiScoreRecord** — file: `mti-scores-api/src/main/java/com/example/mti/model/MtiScoreRecord.java`

Create a Java record `MtiScoreRecord` in package `com.example.mti.model` with the following twelve components corresponding exactly to `mti_scores_history` columns: `Long id`, `String imoNumber`, `Integer year`, `Integer month`, `Double mtiScore`, `Double vesselScore`, `Double reportingScore`, `Double voyagesScore`, `Double emissionsScore`, `Double sanctionsScore`, `OffsetDateTime createdAt`, `OffsetDateTime updatedAt`. Use `java.time.OffsetDateTime` for timestamp fields to preserve UTC offset information from PostgreSQL. All six score fields are boxed `Double` to correctly represent NULL database values as `null` in Java.

**Complexity:** S | **Dependencies:** None

---

#### Story 2.4: Exception Hierarchy

**As a** developer **I want** a typed exception hierarchy for business errors **so that** `GlobalExceptionHandler` can dispatch to the correct HTTP status and error code without `instanceof` chains.

**Background for implementer:** Each exception carries the `ErrorCode` enum value that determines the HTTP status. The `GlobalExceptionHandler` reads `exception.getErrorCode()` to build the response, which keeps the handler stateless and avoids hardcoding HTTP status codes in multiple places. Using a base class (`MtiException`) lets one `@ExceptionHandler(MtiException.class)` method catch all business exceptions, with the specific error code determining the response details.

**Acceptance Criteria:**
- [ ] `ResourceNotFoundException` maps to `ErrorCode.ERR_101`
- [ ] `InvalidParameterException` maps to one of `ERR_102`, `ERR_103`, or `ERR_104` depending on construction
- [ ] Both exceptions extend `MtiException` which carries an `ErrorCode` and optional message override

**Tasks:**

**Task 2.4.a — Create MtiException** — file: `mti-scores-api/src/main/java/com/example/mti/exception/MtiException.java`

Create abstract class `MtiException` extending `RuntimeException` in package `com.example.mti.exception`. Add a `private final ErrorCode errorCode` field. Provide a constructor taking `ErrorCode errorCode` and calling `super(errorCode.getDefaultMessage())`, assigning `this.errorCode = errorCode`. Provide a second constructor taking `ErrorCode errorCode` and `String message` and calling `super(message)`, assigning `this.errorCode = errorCode`. Add getter method `getErrorCode()` returning `ErrorCode`. This class is the base for all business exceptions in the API.

**Task 2.4.b — Create ResourceNotFoundException** — file: `mti-scores-api/src/main/java/com/example/mti/exception/ResourceNotFoundException.java`

Create class `ResourceNotFoundException` extending `MtiException` in package `com.example.mti.exception`. Provide a constructor taking `String imoNumber` and calling `super(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`. Provide a no-arg constructor that calls `super(ErrorCode.ERR_101)`.

**Task 2.4.c — Create InvalidParameterException** — file: `mti-scores-api/src/main/java/com/example/mti/exception/InvalidParameterException.java`

Create class `InvalidParameterException` extending `MtiException` in package `com.example.mti.exception`. Provide a constructor taking `ErrorCode errorCode` and `String message` calling `super(errorCode, message)`. Provide three public static factory methods to cover the three 400 cases: `monthWithoutYear()` returning `new InvalidParameterException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`; `invalidImoFormat(String imo)` returning `new InvalidParameterException(ErrorCode.ERR_103, "IMO number must be exactly 7 digits, received: " + imo)`; `invalidDateRange(String detail)` returning `new InvalidParameterException(ErrorCode.ERR_104, "Invalid year or month value: " + detail)`.

**Complexity:** S | **Dependencies:** Story 2.1

---

### Epic 3: Request Lifecycle Infrastructure

**Goal:** Implement the servlet filters that execute on every request before the controller: request ID injection and rate limiting.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: Request ID Filter

**As a** developer **I want** a servlet filter that generates a UUID v4 request_id on every request and places it in MDC and request attributes **so that** the controller and logs share the same trace identifier.

**Background for implementer:** `OncePerRequestFilter` guarantees single execution per request even when filters are re-invoked (e.g., by Spring's async dispatch). The request_id is stored in MDC under key `"requestId"` so that the `%X{requestId}` pattern in `application.yml` includes it in every log line. It is also stored as a request attribute under `RequestIdFilter.REQUEST_ID_ATTR` so the controller and exception handler can read it when building `MetaDto`.

**Acceptance Criteria:**
- [ ] Every request receives a `requestId` request attribute containing a UUID v4 string
- [ ] MDC key `"requestId"` is populated before `filterChain.doFilter()` and cleared in the finally block
- [ ] The filter is ordered `@Order(1)` to execute before the rate limit filter

**Tasks:**

**Task 3.1.a — Create RequestIdFilter** — file: `mti-scores-api/src/main/java/com/example/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` in package `com.example.mti.filter`, extending `org.springframework.web.filter.OncePerRequestFilter`, annotated with `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"`. Declare `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`. Override method `doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)` throwing `ServletException` and `IOException`. In the method body: generate `String requestId = UUID.randomUUID().toString()`; call `MDC.put("requestId", requestId)`; call `request.setAttribute(REQUEST_ID_ATTR, requestId)`; then execute `filterChain.doFilter(request, response)` inside a try block with a finally block that calls `MDC.remove("requestId")`. Log at DEBUG before invoking the chain: `log.debug("Assigned requestId={}", requestId)`.

**Complexity:** S | **Dependencies:** None

---

#### Story 3.2: Rate Limit Filter

**As a** developer **I want** a Bucket4j token-bucket rate limiter enforcing 100 requests/minute per client IP **so that** the API meets the security constraint defined in the PRD.

**Background for implementer:** Bucket4j's `Bucket` is thread-safe and mutable; one bucket per IP is stored in a `ConcurrentHashMap<String, Bucket>`. The `computeIfAbsent` call atomically creates a bucket if absent. The `tryConsume(1)` call atomically decrements the bucket and returns `false` if empty. Returning 429 before calling `filterChain.doFilter()` short-circuits the filter chain. The IP extraction checks `X-Forwarded-For` first to handle reverse proxies, falling back to `request.getRemoteAddr()`. The filter reads its configuration from `RateLimitConfig.RateLimitProperties` (bound via `@ConfigurationProperties`) to allow tuning via `application.yml` without code changes.

**Acceptance Criteria:**
- [ ] The 101st request within 60 seconds from the same IP receives HTTP 429 with a JSON error body
- [ ] `RateLimitConfig.RateLimitProperties` reads from config keys `app.rate-limit.capacity`, `app.rate-limit.refill-tokens`, `app.rate-limit.refill-period-seconds`
- [ ] The filter is ordered `@Order(2)` to execute after `RequestIdFilter`

**Tasks:**

**Task 3.2.a — Create RateLimitConfig** — file: `mti-scores-api/src/main/java/com/example/mti/config/RateLimitConfig.java`

Create class `RateLimitConfig` in package `com.example.mti.config` annotated with `@Configuration`. Define a nested static class `RateLimitProperties` annotated with `@ConfigurationProperties(prefix = "app.rate-limit")` and `@Component`. Add three fields with standard getters and setters: `int capacity` (default value `100`), `int refillTokens` (default value `100`), `long refillPeriodSeconds` (default value `60`).

**Task 3.2.b — Create RateLimitFilter** — file: `mti-scores-api/src/main/java/com/example/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` in package `com.example.mti.filter`, extending `OncePerRequestFilter`, annotated with `@Component` and `@Order(2)`. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`. Inject `RateLimitConfig.RateLimitProperties properties` via constructor parameter. Declare a field `private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>()`. Override `doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)` throwing `ServletException` and `IOException`. In the method: extract clientIp by checking header `X-Forwarded-For` first — if present, take the first comma-separated token trimmed; otherwise use `request.getRemoteAddr()`; call `Bucket bucket = buckets.computeIfAbsent(clientIp, key -> buildBucket())`; call `boolean consumed = bucket.tryConsume(1L)`. If `consumed` is false: log WARN `log.warn("Rate limit exceeded for ip={}", clientIp)`; set `response.setStatus(429)`; set `response.setContentType("application/json")`; write `{"meta":null,"data":{"error_code":"ERR_429","title":"Too Many Requests","message":"Rate limit exceeded. Max 100 requests per minute."}}` to `response.getWriter()` and return without calling `filterChain.doFilter`. If `consumed` is true: call `filterChain.doFilter(request, response)`. Implement private method `buildBucket()` returning a `Bucket` by calling `Bucket.builder().addLimit(Bandwidth.builder().capacity(properties.getCapacity()).refillGreedy(properties.getRefillTokens(), Duration.ofSeconds(properties.getRefillPeriodSeconds())).build()).build()`.

**Complexity:** M | **Dependencies:** Story 3.1, Story 1.3

---

### Epic 4: Data Access Layer

**Goal:** Implement the repository that executes the three SQL queries defined in the PRD using JdbcTemplate with parameterized queries.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 4.1: MTI Score Repository

**As a** developer **I want** a `MtiScoreRepository` with three query methods **so that** the service layer can retrieve scores without writing SQL.

**Background for implementer:** JdbcTemplate's `query()` method returns a `List`; using it instead of `queryForObject()` avoids having to catch `EmptyResultDataAccessException` in the service layer. The repository takes the first element of the list and wraps it in `Optional.ofNullable`, returning `Optional.empty()` when the list is empty. Use `rs.getObject(column, Double.class)` — not `rs.getDouble()` — in the `RowMapper` because `rs.getDouble()` returns `0.0` for SQL NULL values, which would corrupt the response for AC8 (partial scores).

**Acceptance Criteria:**
- [ ] `findLatest(String imoNumber)` executes query ordered by `year DESC, month DESC LIMIT 1`
- [ ] `findLatestByYear(String imoNumber, int year)` executes query with year filter ordered by `month DESC LIMIT 1`
- [ ] `findByYearAndMonth(String imoNumber, int year, int month)` executes exact match query
- [ ] All methods return `Optional<MtiScoreRecord>` and use parameterized `?` placeholders

**Tasks:**

**Task 4.1.a — Create MtiScoreRepository** — file: `mti-scores-api/src/main/java/com/example/mti/repository/MtiScoreRepository.java`

Create class `MtiScoreRepository` in package `com.example.mti.repository` annotated with `@Repository`. Inject `JdbcTemplate jdbcTemplate` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoreRepository.class)`. Define a private static final `RowMapper<MtiScoreRecord> ROW_MAPPER` field. The lambda implementation must map `ResultSet rs` to a new `MtiScoreRecord` by reading columns in this exact order: `rs.getLong("id")`, `rs.getString("imo_number")`, `rs.getInt("year")`, `rs.getInt("month")`, `rs.getObject("mti_score", Double.class)`, `rs.getObject("vessel_score", Double.class)`, `rs.getObject("reporting_score", Double.class)`, `rs.getObject("voyages_score", Double.class)`, `rs.getObject("emissions_score", Double.class)`, `rs.getObject("sanctions_score", Double.class)`, `rs.getObject("created_at", OffsetDateTime.class)`, `rs.getObject("updated_at", OffsetDateTime.class)`.

Implement method `findLatest(String imoNumber)` returning `Optional<MtiScoreRecord>`. Log at DEBUG: `log.debug("findLatest imo={}", imoNumber)`. Execute the SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1` using `jdbcTemplate.query(sql, ROW_MAPPER, imoNumber)`. Return the first element wrapped in `Optional.ofNullable`, or `Optional.empty()` if the result list is empty.

Implement method `findLatestByYear(String imoNumber, int year)` returning `Optional<MtiScoreRecord>`. Log at DEBUG: `log.debug("findLatestByYear imo={} year={}", imoNumber, year)`. Execute the SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1` with parameters `imoNumber, year`.

Implement method `findByYearAndMonth(String imoNumber, int year, int month)` returning `Optional<MtiScoreRecord>`. Log at DEBUG: `log.debug("findByYearAndMonth imo={} year={} month={}", imoNumber, year, month)`. Execute the SQL `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1` with parameters `imoNumber, year, month`.

**Complexity:** M | **Dependencies:** Story 2.3, Story 1.2

---

### Epic 5: Business Logic Layer

**Goal:** Implement `MtiScoreService` that validates all input parameters, selects the appropriate repository query, and maps the result to the response DTO.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 5.1: MTI Score Service

**As a** developer **I want** a `MtiScoreService` that encapsulates all validation and query-routing logic **so that** the controller remains a thin adapter.

**Background for implementer:** Validation is performed in a specific order to match the PRD error codes: (1) IMO format check → ERR_103; (2) month-without-year check → ERR_102; (3) year range check → ERR_104; (4) month range check → ERR_104; (5) database query → ERR_101 if empty. The `OffsetDateTime` fields on `MtiScoreRecord` are converted to ISO-8601 strings via `OffsetDateTime.toString()` when populating `ScoreMetadataDto`.

**Acceptance Criteria:**
- [ ] IMO not matching `^[0-9]{7}$` throws `InvalidParameterException.invalidImoFormat(imo)`
- [ ] Month specified without year throws `InvalidParameterException.monthWithoutYear()`
- [ ] Year outside 2000–2100 throws `InvalidParameterException.invalidDateRange("year=" + year)`
- [ ] Month outside 1–12 throws `InvalidParameterException.invalidDateRange("month=" + month)`
- [ ] No result from repository throws `ResourceNotFoundException(imoNumber)`
- [ ] Returns `ScoreDataDto` with all fields populated from `MtiScoreRecord`

**Tasks:**

**Task 5.1.a — Create MtiScoreService** — file: `mti-scores-api/src/main/java/com/example/mti/service/MtiScoreService.java`

Create class `MtiScoreService` in package `com.example.mti.service` annotated with `@Service`. Inject `MtiScoreRepository repository` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoreService.class)`. Declare `private static final Pattern IMO_PATTERN = Pattern.compile("^[0-9]{7}$")`.

Implement method `getScores(String imoNumber, Integer year, Integer month)` returning `ScoreDataDto`. The method body must follow this exact validation order:

Step 1 — if `!IMO_PATTERN.matcher(imoNumber).matches()`: log WARN `log.warn("Invalid IMO format imo={}", imoNumber)` and throw `InvalidParameterException.invalidImoFormat(imoNumber)`.

Step 2 — if `month != null && year == null`: log WARN `log.warn("Month specified without year imo={}", imoNumber)` and throw `InvalidParameterException.monthWithoutYear()`.

Step 3 — if `year != null && (year < 2000 || year > 2100)`: log WARN `log.warn("Invalid year value year={}", year)` and throw `InvalidParameterException.invalidDateRange("year=" + year)`.

Step 4 — if `month != null && (month < 1 || month > 12)`: log WARN `log.warn("Invalid month value month={}", month)` and throw `InvalidParameterException.invalidDateRange("month=" + month)`.

Step 5 — log at INFO: `log.info("Fetching MTI scores imo={} year={} month={}", imoNumber, year, month)`.

Step 6 — determine repository method: if `year == null` call `repository.findLatest(imoNumber)`; else if `month == null` call `repository.findLatestByYear(imoNumber, year)`; else call `repository.findByYearAndMonth(imoNumber, year, month)`. Store result as `Optional<MtiScoreRecord> result`.

Step 7 — if `result.isEmpty()`: log WARN `log.warn("No MTI scores found imo={} year={} month={}", imoNumber, year, month)` and throw `new ResourceNotFoundException(imoNumber)`.

Step 8 — extract `MtiScoreRecord record = result.get()`.

Step 9 — build and return `new ScoreDataDto(record.imoNumber(), record.year(), record.month(), new ScoresDto(record.mtiScore(), record.vesselScore(), record.reportingScore(), record.voyagesScore(), record.emissionsScore(), record.sanctionsScore()), new ScoreMetadataDto(record.createdAt().toString(), record.updatedAt().toString()))`.

**Complexity:** M | **Dependencies:** Story 4.1, Story 2.4, Story 2.2

---

### Epic 6: API Layer

**Goal:** Implement the REST controller and global exception handler that expose the endpoint defined in the PRD.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 6.1: REST Controller

**As a** developer **I want** `MtiScoreController` mapped to `GET /api/v1/vessels/{imo}/mti-scores` **so that** HTTP clients can retrieve scores.

**Background for implementer:** The controller reads the `requestId` from the request attribute set by `RequestIdFilter` using the constant `RequestIdFilter.REQUEST_ID_ATTR`. The `request_timestamp` is the current UTC time formatted as ISO-8601 via `DateTimeFormatter.ISO_OFFSET_DATE_TIME`. The controller delegates all validation and business logic to `MtiScoreService`; its only responsibilities are: extract parameters, build the meta object, invoke service, and wrap the result.

**Acceptance Criteria:**
- [ ] Path is `GET /api/v1/vessels/{imo}/mti-scores`
- [ ] Returns HTTP 200 with `SuccessResponseDto` on success
- [ ] `meta.request_id` equals the UUID set by `RequestIdFilter`
- [ ] `meta.request_timestamp` is in ISO-8601 UTC format

**Tasks:**

**Task 6.1.a — Create MtiScoreController** — file: `mti-scores-api/src/main/java/com/example/mti/controller/MtiScoreController.java`

Create class `MtiScoreController` in package `com.example.mti.controller` annotated with `@RestController` and `@RequestMapping("/api/v1/vessels")`. Inject `MtiScoreService mtiScoreService` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoreController.class)`.

Implement method `getMtiScores(String imo, Integer year, Integer month, HttpServletRequest httpRequest)` returning `ResponseEntity<SuccessResponseDto>`, annotated with `@GetMapping("/{imo}/mti-scores")`. Annotate the `imo` parameter with `@PathVariable`. Annotate `year` with `@RequestParam(required = false)`. Annotate `month` with `@RequestParam(required = false)`. The `HttpServletRequest httpRequest` parameter requires no annotation; Spring MVC injects it automatically.

In the method body: extract `String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if `requestId` is null assign `requestId = UUID.randomUUID().toString()` as a fallback; build `String requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)`; log at INFO: `log.info("GET mti-scores imo={} year={} month={} requestId={}", imo, year, month, requestId)`; call `ScoreDataDto data = mtiScoreService.getScores(imo, year, month)`; build `MetaDto meta = new MetaDto(requestId, requestTimestamp)`; return `ResponseEntity.ok(new SuccessResponseDto(meta, data))`.

**Complexity:** M | **Dependencies:** Story 5.1, Story 3.1, Story 2.2

---

#### Story 6.2: Global Exception Handler

**As a** developer **I want** a `GlobalExceptionHandler` that maps all exceptions to the error response format defined in the PRD **so that** clients always receive consistent JSON error envelopes.

**Background for implementer:** `@RestControllerAdvice` combines `@ControllerAdvice` and `@ResponseBody`, so exception handler return values are automatically serialized as JSON. The `WebRequest` parameter provides access to the underlying `HttpServletRequest` via cast to `ServletWebRequest` for reading the `requestId` attribute. Handler ordering: the `@ExceptionHandler(MtiException.class)` method catches all business exceptions before the generic `@ExceptionHandler(Exception.class)` method — Spring processes the most specific handler first.

**Acceptance Criteria:**
- [ ] `ResourceNotFoundException` → 404 with `error_code = "ERR_101"`
- [ ] `InvalidParameterException` with ERR_102/103/104 → 400 with matching `error_code`
- [ ] Any other `Exception` → 500 with `error_code = "ERR_105"` and no stack trace in response
- [ ] All error responses include a populated `meta` object with `request_id` and `request_timestamp`

**Tasks:**

**Task 6.2.a — Create GlobalExceptionHandler** — file: `mti-scores-api/src/main/java/com/example/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in package `com.example.mti.exception` annotated with `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`.

Implement private helper method `buildMeta(WebRequest request)` returning `MetaDto`. In the method: cast `request` to `ServletWebRequest` and call `.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` to get `String requestId`; if null use `UUID.randomUUID().toString()`; build `String requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)`; return `new MetaDto(requestId, requestTimestamp)`.

Implement method `handleMtiException(MtiException ex, WebRequest request)` returning `ResponseEntity<ErrorResponseDto>` annotated with `@ExceptionHandler(MtiException.class)`. In the method: log WARN `log.warn("Business exception errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage())`; build `ErrorDataDto data = new ErrorDataDto(ex.getErrorCode().getCode(), ex.getErrorCode().getTitle(), ex.getMessage())`; build `ErrorResponseDto body = new ErrorResponseDto(buildMeta(request), data)`; return `ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body)`.

Implement method `handleGenericException(Exception ex, WebRequest request)` returning `ResponseEntity<ErrorResponseDto>` annotated with `@ExceptionHandler(Exception.class)`. In the method: log ERROR `log.error("Unexpected exception message={}", ex.getMessage(), ex)`; build `ErrorDataDto data = new ErrorDataDto(ErrorCode.ERR_105.getCode(), ErrorCode.ERR_105.getTitle(), ErrorCode.ERR_105.getDefaultMessage())`; return `ResponseEntity.status(500).body(new ErrorResponseDto(buildMeta(request), data))`.

**Complexity:** M | **Dependencies:** Story 2.4, Story 2.2, Story 3.1

---

### Epic 7: API Documentation

**Goal:** Expose auto-generated OpenAPI 3.0 docs via SpringDoc so developers can explore the API without reading source code.
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 7.1: OpenAPI Configuration

**As a** developer **I want** a Swagger UI available at `/swagger-ui.html` **so that** API consumers can explore the endpoint interactively.

**Acceptance Criteria:**
- [ ] `GET /api-docs` returns the OpenAPI JSON spec
- [ ] `GET /swagger-ui.html` renders the Swagger UI

**Tasks:**

**Task 7.1.a — Create OpenApiConfig** — file: `mti-scores-api/src/main/java/com/example/mti/config/OpenApiConfig.java`

Create class `OpenApiConfig` in package `com.example.mti.config` annotated with `@Configuration`. Declare a `@Bean` method named `openAPI()` returning `io.swagger.v3.oas.models.OpenAPI`. In the method body: build and return `new OpenAPI().info(new Info().title("MTI Scores API").version("1.0.0").description("API for retrieving Maritime Transportation Indicator scores by vessel IMO number"))`. SpringDoc will auto-discover `MtiScoreController` and generate path item definitions; no additional annotations are needed on the controller for basic documentation.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 8: Testing

**Goal:** Achieve ≥80% line coverage with unit tests for the service and controller, and verify end-to-end behavior with a Testcontainers integration test against a real PostgreSQL instance.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 8.1: Service Unit Tests

**As a** developer **I want** unit tests covering all eight acceptance criteria for `MtiScoreService` **so that** all business rules are verified in isolation.

**Acceptance Criteria:**
- [ ] Eight test methods, one per acceptance criterion (AC1–AC8)
- [ ] Repository is mocked with Mockito; no Spring context is loaded
- [ ] All five error codes are tested via their exception types
- [ ] AC8 test verifies that NULL score fields are passed through as `null` in `ScoresDto`

**Tasks:**

**Task 8.1.a — Create MtiScoreServiceTest** — file: `mti-scores-api/src/test/java/com/example/mti/service/MtiScoreServiceTest.java`

Create class `MtiScoreServiceTest` in package `com.example.mti.service` annotated with `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoreRepository repository`. Declare `@InjectMocks MtiScoreService service`. Declare a private helper method `buildRecord(String imoNumber, int year, int month, Double mtiScore, Double vesselScore, Double reportingScore, Double voyagesScore, Double emissionsScore, Double sanctionsScore)` that constructs a `MtiScoreRecord` with id=`1L`, the given field values, createdAt=`OffsetDateTime.parse("2024-01-01T00:00:00Z")`, updatedAt=`OffsetDateTime.parse("2024-01-01T00:00:00Z")`.

Implement the following eight test methods:

`getScores_latestScores_returnsScoreData()` — Mock `repository.findLatest("9123456")` to return `Optional.of(buildRecord("9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00))`. Call `service.getScores("9123456", null, null)`. Assert the returned `ScoreDataDto` has `imoNumber()` equal to `"9123456"`, `year()` equal to `2024`, `month()` equal to `1`, `scores().mtiScore()` equal to `85.50` (within delta 0.001).

`getScores_specificYear_callsFindLatestByYear()` — Mock `repository.findLatestByYear("9123456", 2023)` to return `Optional.of(buildRecord("9123456", 2023, 12, 82.00, 87.00, 83.00, 79.00, 83.00, 97.00))`. Call `service.getScores("9123456", 2023, null)`. Assert `year()` equals `2023` and `month()` equals `12`. Verify via `Mockito.verify(repository).findLatestByYear("9123456", 2023)` was called exactly once.

`getScores_specificYearAndMonth_callsFindByYearAndMonth()` — Mock `repository.findByYearAndMonth("9123456", 2023, 6)` to return `Optional.of(buildRecord("9123456", 2023, 6, 80.00, 85.00, 82.00, 78.00, 81.00, 95.00))`. Call `service.getScores("9123456", 2023, 6)`. Assert `month()` equals `6`.

`getScores_imoNotFound_throwsResourceNotFoundException()` — Mock `repository.findLatest("9999999")` to return `Optional.empty()`. Call `service.getScores("9999999", null, null)` inside `assertThrows(ResourceNotFoundException.class, () -> ...)`. Assert the exception message contains `"9999999"`.

`getScores_invalidImoFormat_throwsInvalidParameterException()` — Call `service.getScores("123", null, null)` inside `assertThrows(InvalidParameterException.class, () -> ...)`. Assert `exception.getErrorCode() == ErrorCode.ERR_103`. Verify `Mockito.verifyNoInteractions(repository)`.

`getScores_monthWithoutYear_throwsInvalidParameterException()` — Call `service.getScores("9123456", null, 6)` inside `assertThrows`. Assert `exception.getErrorCode() == ErrorCode.ERR_102`.

`getScores_invalidMonthValue_throwsInvalidParameterException()` — Call `service.getScores("9123456", 2023, 13)` inside `assertThrows`. Assert `exception.getErrorCode() == ErrorCode.ERR_104`.

`getScores_partialNullScores_returnsNullFields()` — Mock `repository.findLatest("9123456")` to return `Optional.of(buildRecord("9123456", 2022, 3, 75.00, null, 79.00, null, 80.00, 90.00))`. Call `service.getScores("9123456", null, null)`. Assert `result.scores().vesselScore()` is `null`. Assert `result.scores().voyagesScore()` is `null`. Assert `result.scores().mtiScore()` equals `75.00` (within delta 0.001).

**Complexity:** M | **Dependencies:** Story 5.1

---

#### Story 8.2: Controller Unit Tests

**As a** developer **I want** `@WebMvcTest` slice tests for `MtiScoreController` **so that** request mapping, parameter binding, and HTTP response codes are verified without a database.

**Acceptance Criteria:**
- [ ] HTTP 200 is returned for a valid request with correct JSON structure
- [ ] HTTP 404 is returned when service throws `ResourceNotFoundException`
- [ ] HTTP 400 is returned for ERR_102, ERR_103 cases
- [ ] HTTP 500 is returned for unexpected `RuntimeException`
- [ ] `$.meta.request_id` is a valid UUID string in all responses

**Tasks:**

**Task 8.2.a — Create MtiScoreControllerTest** — file: `mti-scores-api/src/test/java/com/example/mti/controller/MtiScoreControllerTest.java`

Create class `MtiScoreControllerTest` annotated with `@WebMvcTest(MtiScoreController.class)` and `@ActiveProfiles("test")`. Declare `@Autowired MockMvc mockMvc`. Declare `@MockBean MtiScoreService mtiScoreService`. Since `@WebMvcTest` loads filter beans, also declare `@MockBean RateLimitConfig.RateLimitProperties rateLimitProperties` and configure the `RateLimitFilter` to not interfere (or annotate `RateLimitFilter` with `@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)` and set `app.rate-limit.enabled=false` in `application-test.yml`).

Implement the following test methods:

`getMtiScores_validRequest_returns200()` — Mock `mtiScoreService.getScores("9123456", null, null)` to return `new ScoreDataDto("9123456", 2024, 1, new ScoresDto(85.50, 90.00, 88.75, 82.30, 87.60, 100.00), new ScoreMetadataDto("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z"))`. Perform `mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/9123456/mti-scores"))`. Assert `.andExpect(status().isOk())`. Assert `.andExpect(jsonPath("$.meta.request_id").isNotEmpty())`. Assert `.andExpect(jsonPath("$.data.imo_number").value("9123456"))`. Assert `.andExpect(jsonPath("$.data.year").value(2024))`. Assert `.andExpect(jsonPath("$.data.scores.mti_score").value(85.5))`.

`getMtiScores_imoNotFound_returns404()` — Mock service to throw `new ResourceNotFoundException("9999999")`. Perform `GET /api/v1/vessels/9999999/mti-scores`. Assert status `404`. Assert `.andExpect(jsonPath("$.data.error_code").value("ERR_101"))`. Assert `.andExpect(jsonPath("$.meta.request_id").isNotEmpty())`.

`getMtiScores_monthWithoutYear_returns400()` — Mock service to throw `InvalidParameterException.monthWithoutYear()` when called with `("9123456", null, 6)`. Perform `GET /api/v1/vessels/9123456/mti-scores?month=6`. Assert status `400`. Assert `.andExpect(jsonPath("$.data.error_code").value("ERR_102"))`.

`getMtiScores_invalidImo_returns400()` — Mock service to throw `InvalidParameterException.invalidImoFormat("123")` when called with `("123", null, null)`. Perform `GET /api/v1/vessels/123/mti-scores`. Assert status `400`. Assert `.andExpect(jsonPath("$.data.error_code").value("ERR_103"))`.

`getMtiScores_internalError_returns500()` — Mock service to throw `new RuntimeException("DB down")` when called with `("9123456", null, null)`. Perform `GET /api/v1/vessels/9123456/mti-scores`. Assert status `500`. Assert `.andExpect(jsonPath("$.data.error_code").value("ERR_105"))`.

**Complexity:** M | **Dependencies:** Story 6.1, Story 6.2

---

#### Story 8.3: Integration Tests

**As a** developer **I want** a Testcontainers integration test that runs against a real PostgreSQL container **so that** the full request-to-database path is verified.

**Background for implementer:** Testcontainers starts a Docker PostgreSQL container before the test class and provides the JDBC URL via `@DynamicPropertySource`. Flyway runs the V1 and V2 migrations against this container on Spring context startup. The `application-test.yml` sets `spring.flyway.enabled=false`, so the `@DynamicPropertySource` method must override `spring.flyway.enabled` to `"true"` along with the datasource properties.

**Acceptance Criteria:**
- [ ] All eight acceptance criteria (AC1–AC8) pass against the real PostgreSQL container with V2 seed data
- [ ] Rate limit is not triggered (test profile sets capacity to 1000)

**Tasks:**

**Task 8.3.a — Create MtiScoreIntegrationTest** — file: `mti-scores-api/src/test/java/com/example/mti/integration/MtiScoreIntegrationTest.java`

Create class `MtiScoreIntegrationTest` annotated with `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, `@ActiveProfiles("test")`, and `@Testcontainers`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Declare a `@LocalServerPort int port` field for the random port. Declare `@Autowired TestRestTemplate restTemplate`. Add a static method annotated with `@DynamicPropertySource` taking parameter `DynamicPropertyRegistry registry`: register `spring.datasource.url` from `postgres.getJdbcUrl()`, `spring.datasource.username` from `postgres.getUsername()`, `spring.datasource.password` from `postgres.getPassword()`, and `spring.flyway.enabled` as the string `"true"`.

Implement the following eight test methods using `@Test`:

`ac1_latestScores_returns200WithImo9123456()` — Call `restTemplate.getForEntity("/api/v1/vessels/9123456/mti-scores", String.class)`. Assert `response.getStatusCode()` is `HttpStatus.OK`. Parse response body as JSON. Assert `$.data.imo_number` equals `"9123456"`. Assert `$.data.year` equals `2024`. Assert `$.data.month` equals `1`.

`ac2_specificYear_returnsLatestMonthFor2023()` — Call `GET /api/v1/vessels/9123456/mti-scores?year=2023`. Assert status `200`. Assert `$.data.year` equals `2023`. Assert `$.data.month` equals `12` (month 12 is latest in 2023 per seed data).

`ac3_specificYearAndMonth_returnsJune2023()` — Call `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6`. Assert status `200`. Assert `$.data.year` equals `2023`. Assert `$.data.month` equals `6`. Assert `$.data.scores.mti_score` equals `80.0`.

`ac4_imoNotFound_returns404WithErrCode101()` — Call `GET /api/v1/vessels/9999999/mti-scores`. Assert status `404`. Assert `$.data.error_code` equals `"ERR_101"`. Assert `$.data.message` contains `"9999999"`.

`ac5_invalidImoFormat_returns400WithErrCode103()` — Call `GET /api/v1/vessels/123/mti-scores`. Assert status `400`. Assert `$.data.error_code` equals `"ERR_103"`.

`ac6_monthWithoutYear_returns400WithErrCode102()` — Call `GET /api/v1/vessels/9123456/mti-scores?month=6`. Assert status `400`. Assert `$.data.error_code` equals `"ERR_102"`.

`ac7_invalidMonthValue13_returns400WithErrCode104()` — Call `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13`. Assert status `400`. Assert `$.data.error_code` equals `"ERR_104"`.

`ac8_partialNullScores_returnsNullFieldsInJson()` — Call `GET /api/v1/vessels/9123456/mti-scores?year=2022&month=3`. Assert status `200`. Assert `$.data.scores.vessel_score` is JSON null. Assert `$.data.scores.voyages_score` is JSON null. Assert `$.data.scores.mti_score` equals `75.0`.

**Complexity:** L | **Dependencies:** Story 1.2, Story 6.1, Story 6.2

---

### Epic 9: Containerization

**Goal:** Package the application as a Docker image and provide a docker-compose setup for local development.
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 9.1: Docker Configuration

**As a** developer **I want** a Dockerfile and docker-compose.yml **so that** the service can be started locally with a single `docker-compose up` command.

**Acceptance Criteria:**
- [ ] `docker build -t mti-scores-api .` succeeds
- [ ] `docker-compose up` starts both the API and a PostgreSQL container
- [ ] The API container connects to PostgreSQL using environment variables `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`

**Tasks:**

**Task 9.1.a — Create Dockerfile** — file: `mti-scores-api/Dockerfile`

Create a multi-stage Dockerfile. Stage 1 named `builder` uses base image `maven:3.9.6-eclipse-temurin-17-alpine`. Set WORKDIR to `/build`. Copy `pom.xml` first, then run `mvn dependency:go-offline -q` to cache dependencies in a separate layer. Then copy `src/` and run `mvn clean package -DskipTests -q` to build the JAR. Stage 2 uses base image `eclipse-temurin:17-jre-alpine`. Set WORKDIR to `/app`. Copy the JAR from the builder stage: `COPY --from=builder /build/target/mti-scores-api-0.0.1-SNAPSHOT.jar app.jar`. Expose port `8080`. Set `ENTRYPOINT ["java", "-jar", "app.jar"]`.

**Task 9.1.b — Create docker-compose.yml** — file: `mti-scores-api/docker-compose.yml`

Create a Docker Compose file (version `"3.8"`) defining two services. Service `db` uses image `postgres:15-alpine`, sets environment variables `POSTGRES_DB=mti`, `POSTGRES_USER=mti`, `POSTGRES_PASSWORD=mti`, maps port `5432:5432`, and uses a named volume `postgres_data` at `/var/lib/postgresql/data`. Service `api` uses `build: .` to build from the local Dockerfile, declares `depends_on: [db]`, sets environment variables `DATABASE_URL=jdbc:postgresql://db:5432/mti`, `DATABASE_USERNAME=mti`, `DATABASE_PASSWORD=mti`, and maps port `8080:8080`. Define a top-level `volumes` key with entry `postgres_data`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     String    Required    7-digit IMO number matching regex ^[0-9]{7}$

Query Parameters:
  year    Integer   Optional    Year filter (2000–2100 inclusive)
  month   Integer   Optional    Month filter (1–12 inclusive); requires year parameter

Request Headers:
  (none required)

Success Response — 200:
  meta.request_id               String    UUID v4 (e.g. "550e8400-e29b-41d4-a716-446655440000")
  meta.request_timestamp        String    ISO-8601 UTC (e.g. "2024-01-15T10:30:00Z")
  data.imo_number               String    7-digit IMO
  data.year                     Integer   Score year
  data.month                    Integer   Score month (1–12)
  data.scores.mti_score         Float?    Nullable
  data.scores.vessel_score      Float?    Nullable
  data.scores.reporting_score   Float?    Nullable
  data.scores.voyages_score     Float?    Nullable
  data.scores.emissions_score   Float?    Nullable
  data.scores.sanctions_score   Float?    Nullable
  data.metadata.created_at      String    ISO-8601 timestamp
  data.metadata.updated_at      String    ISO-8601 timestamp

Error Response — 4XX / 5XX:
  meta.request_id               String    Same UUID as success meta
  meta.request_timestamp        String    ISO-8601 UTC
  data.error_code               String    One of ERR_101..ERR_105
  data.title                    String    Human-readable error title
  data.message                  String    Error detail message (no stack trace)

Error Code Reference:
  ERR_101   404   No MTI scores found for the given IMO/year/month combination
  ERR_102   400   Month parameter specified without year parameter
  ERR_103   400   IMO number is not exactly 7 digits
  ERR_104   400   Year outside 2000–2100 or month outside 1–12
  ERR_105   500   Database or unexpected internal server error
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 100ms; composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` mandatory |
| Logging | SLF4J + Logback; console pattern includes `%X{requestId}`; DEBUG for repository queries, INFO for service/controller entry, WARN for business errors, ERROR for unexpected exceptions |
| Metrics | Spring Boot Actuator `/actuator/health` exposes liveness; extend with Micrometer + Prometheus if metrics scraping is required |
| Security | Parameterized JdbcTemplate queries only; no string interpolation in SQL; no stack traces exposed in error responses |
| Rate Limiting | Token bucket via Bucket4j 8.10.1; 100 tokens/60 seconds per client IP; in-process ConcurrentHashMap; returns HTTP 429 with JSON error body |
| Testing | ≥80% line coverage; unit tests with Mockito (no Spring context); integration tests with Testcontainers PostgreSQL 15 |
| Health / Docs | `GET /actuator/health` → `{"status":"UP"}`; `GET /swagger-ui.html` → Swagger UI; `GET /api-docs` → OpenAPI JSON |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `MtiScoreController` | `MtiScoreService` | Delegates all validation and query routing |
| `MtiScoreController` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request_id attribute to populate MetaDto |
| `MtiScoreService` | `MtiScoreRepository` | Issues the three database queries |
| `MtiScoreService` | `ErrorCode` (via exception factory methods) | Creates typed exceptions with correct error codes |
| `MtiScoreService` | `InvalidParameterException`, `ResourceNotFoundException` | Throws on validation failure or not-found |
| `GlobalExceptionHandler` | `MtiException` | Catches all business exceptions via base type |
| `GlobalExceptionHandler` | `ErrorCode` | Reads HTTP status, code, and title from enum constants |
| `GlobalExceptionHandler` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads request_id attribute for error response meta |
| `RateLimitFilter` | `RateLimitConfig.RateLimitProperties` | Reads capacity and refill period configuration |
| `MtiScoreRepository` | `JdbcTemplate` | Executes parameterized SQL queries |
| `RequestIdFilter` | `@Order(1)` | Must execute before `RateLimitFilter` to set requestId in MDC |
| `RateLimitFilter` | `@Order(2)` | Must execute after `RequestIdFilter` |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Maven project must exist before anything else can compile
2. **Story 2.1** — `ErrorCode` enum has no dependencies; all exception classes depend on it
3. **Stories 2.2, 2.3, 2.4** — DTOs, domain model, exception hierarchy are independent of each other and can proceed in parallel
4. **Stories 1.2, 1.3** — Database migrations and configuration are independent of domain types; can proceed in parallel with Epic 2
5. **Story 3.1** — `RequestIdFilter` has no business dependencies; needed by controller and exception handler
6. **Story 4.1** — `MtiScoreRepository` depends on `MtiScoreRecord` (2.3) and migration (1.2)
7. **Story 5.1** — `MtiScoreService` depends on repository (4.1), DTOs (2.2), and exceptions (2.4)
8. **Stories 3.2, 6.1, 6.2** — Rate limit filter, controller, and exception handler each depend on Story 5.1; can proceed in parallel
9. **Story 7.1** — OpenAPI config depends only on `springdoc` being on the classpath
10. **Stories 8.1, 8.2** — Service and controller unit tests can be written in parallel once their targets are complete
11. **Story 8.3** — Integration tests depend on all production code and the V2 seed migration
12. **Story 9.1** — Docker packaging is the final delivery step

> Stories 2.2, 2.3, and 2.4 can be developed simultaneously after Story 2.1. Stories 3.2, 6.1, and 6.2 can be developed simultaneously after Story 5.1. Stories 8.1 and 8.2 can be written in parallel.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `MetaDto` | `requestId: String, requestTimestamp: String` | `request_id, request_timestamp` | Included in both success and error responses |
| `ScoresDto` | `mtiScore: Double?, vesselScore: Double?, reportingScore: Double?, voyagesScore: Double?, emissionsScore: Double?, sanctionsScore: Double?` | `mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score` | All fields nullable (boxed Double) |
| `ScoreMetadataDto` | `createdAt: String, updatedAt: String` | `created_at, updated_at` | ISO-8601 string; nested inside ScoreDataDto |
| `ScoreDataDto` | `imoNumber: String, year: Integer, month: Integer, scores: ScoresDto, metadata: ScoreMetadataDto` | `imo_number, year, month, scores, metadata` | Success response data payload |
| `SuccessResponseDto` | `meta: MetaDto, data: ScoreDataDto` | `meta, data` | Top-level HTTP 200 response envelope |
| `ErrorDataDto` | `errorCode: String, title: String, message: String` | `error_code, title, message` | Error payload nested under `data` |
| `ErrorResponseDto` | `meta: MetaDto, data: ErrorDataDto` | `meta, data` | Top-level 4XX/5XX response envelope |
| `MtiScoreRecord` | `id: Long, imoNumber: String, year: Integer, month: Integer, mtiScore: Double?, vesselScore: Double?, reportingScore: Double?, voyagesScore: Double?, emissionsScore: Double?, sanctionsScore: Double?, createdAt: OffsetDateTime, updatedAt: OffsetDateTime` | (internal model, not serialized to JSON) | Maps directly to `mti_scores_history` columns |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | Yes (prod) | `jdbc:postgresql://localhost:5432/mti` | JDBC connection URL for PostgreSQL |
| `DATABASE_USERNAME` | Yes (prod) | `mti` | PostgreSQL database username |
| `DATABASE_PASSWORD` | Yes (prod) | `mti` | PostgreSQL database password; use a secrets manager in production |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | Part of unique constraint `uq_mti_scores_imo_year_month` |
| `year` | INTEGER | No | Part of unique constraint |
| `month` | INTEGER | No | Part of unique constraint |
| `mti_score` | NUMERIC(5,2) | Yes | |
| `vessel_score` | NUMERIC(5,2) | Yes | |
| `reporting_score` | NUMERIC(5,2) | Yes | |
| `voyages_score` | NUMERIC(5,2) | Yes | |
| `emissions_score` | NUMERIC(5,2) | Yes | |
| `sanctions_score` | NUMERIC(5,2) | Yes | |
| `created_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |

Additional constraints:
- Unique constraint `uq_mti_scores_imo_year_month` on `(imo_number, year, month)`
- Composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` for ordered queries

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| In-process rate limiter does not work across multiple instances | M | M | Document that production must use Redis-backed Bucket4j; in-process is sufficient for single-node MVP |
| NULL scores return `0.0` instead of `null` in JSON | M | H | Use `rs.getObject(col, Double.class)` in RowMapper — not `rs.getDouble()` which returns `0.0` for SQL NULL |
| Flyway V2 seed data applies in production | M | L | Remove V2 migration or restrict `spring.flyway.locations` to exclude seed migrations before production deploy |
| `X-Forwarded-For` header spoofing bypasses rate limit | M | M | Accept as known limitation for MVP; full mitigation requires API gateway IP whitelisting |
| Testcontainers requires Docker daemon in CI | M | M | Ensure CI runner has Docker; fall back to H2-only unit tests if Docker is unavailable in CI |
| PostgreSQL NUMERIC(5,2) insufficient for edge case scores | L | L | Score range is 0–100; NUMERIC(5,2) supports up to 999.99; no overflow risk for this domain |

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
- [ ] Performance targets verified under load (p95 < 100ms)
- [ ] Security review passed (parameterized queries verified; no stack traces in responses)
- [ ] API documentation accessible at `GET /swagger-ui.html`
- [ ] Docker image builds and runs locally via `docker-compose up`
- [ ] Environment variables `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` documented in README

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Maven project bootstrap; everything depends on a compilable project
2. **Story 2.1** — `ErrorCode` enum; no dependencies; needed by exception hierarchy
3. **Stories 2.2, 2.3, 2.4** — DTOs, domain model, exception hierarchy (all can proceed in parallel)
4. **Stories 1.2, 1.3** — Database migrations and application configuration (parallel with Epic 2)
5. **Story 3.1** — `RequestIdFilter`; needed by controller and exception handler
6. **Story 4.1** — `MtiScoreRepository`; depends on domain model (2.3) and migrations (1.2)
7. **Story 5.1** — `MtiScoreService`; depends on repository (4.1), DTOs (2.2), and exceptions (2.4)
8. **Stories 3.2, 6.1, 6.2** — Rate limit filter, controller, and exception handler (all can proceed in parallel once 5.1 is done)
9. **Story 7.1** — OpenAPI config; depends only on classpath
10. **Stories 8.1, 8.2** — Unit tests for service and controller (parallel tracks)
11. **Story 8.3** — Integration tests; depends on all production code and V2 seed data
12. **Story 9.1** — Docker configuration; final packaging step

> Stories 2.2, 2.3, and 2.4 can be developed simultaneously after Story 2.1. Stories 3.2, 6.1, and 6.2 can be developed simultaneously after Story 5.1. Stories 8.1 and 8.2 can be written in parallel.
