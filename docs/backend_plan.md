## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.example.mti` | **Group ID:** `com.example` | **Artifact ID:** `mti-scores-api`

### Overview

The backend is a single Spring Boot application exposing one REST endpoint. Data access uses Spring JdbcTemplate — no ORM — so query structure matches the PRD SQL exactly and column mapping is explicit. Cross-cutting concerns (request ID propagation, rate limiting, exception translation) are handled by servlet filters ordered 1 and 2, plus a `@RestControllerAdvice`. Flyway manages schema migration on startup before the servlet container accepts requests.

---

### Epic 1: Project Foundation

**Goal:** Establish a compilable Spring Boot 3.2 Maven project with all dependencies, configuration, and database schema ready for feature development.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Maven Project Setup

**As a** developer **I want** a compilable Spring Boot 3.2 Maven project with all required dependencies declared **so that** all subsequent stories can add code without any further build configuration changes.

**Acceptance Criteria:**
- [ ] `source/pom.xml` declares Spring Boot 3.2.x parent, Java 17 source/target, and all required dependencies
- [ ] `source/src/main/java/com/example/mti/MtiApplication.java` annotated `@SpringBootApplication` with a `main` method
- [ ] `mvn clean compile` succeeds with no errors
- [ ] `source/src/main/resources/application.yml` externalizes all datasource and rate-limit config via env vars

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `source/pom.xml`

Create the Maven project descriptor. Set `groupId` to `com.example`, `artifactId` to `mti-scores-api`, `version` to `1.0.0-SNAPSHOT`, `packaging` to `jar`. Use `spring-boot-starter-parent` version `3.2.4` as the parent. Set `java.version` property to `17`. Declare the following `<dependencies>`: `spring-boot-starter-web` (embedded Tomcat, Spring MVC), `spring-boot-starter-jdbc` (JdbcTemplate, DataSource), `spring-boot-starter-validation` (Bean Validation 3.0), `spring-boot-starter-actuator` (health endpoint), `org.flywaydb:flyway-core` version `9.22.3`, `org.postgresql:postgresql` version `42.7.3` with `<scope>runtime</scope>`, `io.github.bucket4j:bucket4j-core` version `8.9.0`, `org.springdoc:springdoc-openapi-starter-webmvc-ui` version `2.3.0`, `org.springframework.boot:spring-boot-starter-test` with `<scope>test</scope>`, `org.testcontainers:junit-jupiter` version `1.19.6` with `<scope>test</scope>`, `org.testcontainers:postgresql` version `1.19.6` with `<scope>test</scope>`. Add the `spring-boot-maven-plugin` in `<build><plugins>` with `<goal>repackage</goal>`.

**Task 1.1.b — Create MtiApplication.java** — file: `source/src/main/java/com/example/mti/MtiApplication.java`

Create class `MtiApplication` in package `com.example.mti`. Annotate with `@SpringBootApplication`. Add `public static void main(String[] args)` that calls `SpringApplication.run(MtiApplication.class, args)`. No additional configuration is needed — Spring Boot auto-configuration handles DataSource, Flyway, embedded Tomcat, and JdbcTemplate bean creation.

**Task 1.1.c — Create application.yml** — file: `source/src/main/resources/application.yml`

Create the application configuration file. Under `spring.datasource`: set `url` to `${DATABASE_URL:jdbc:postgresql://localhost:5432/mtidb}`, `username` to `${DATABASE_USERNAME:mti}`, `password` to `${DATABASE_PASSWORD:mti}`, `driver-class-name` to `org.postgresql.Driver`. Under `spring.flyway`: set `enabled` to `true`, `locations` to `classpath:db/migration`. Under `server`: set `port` to `${SERVER_PORT:8080}`. Under `management.endpoints.web.exposure`: set `include` to `health`. Under `springdoc`: set `api-docs.path` to `/api-docs`, `swagger-ui.path` to `/swagger-ui.html`. Under `app.rate-limit`: set `requests-per-minute` to `100`.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Database Schema Migration

**As a** developer **I want** the `mti_scores_history` table created by a Flyway migration **so that** the application starts cleanly against a fresh PostgreSQL 15 instance and all queries have the correct schema and performance indexes.

**Background for implementer:** Flyway runs `V1__create_mti_scores_history.sql` automatically on application startup (before the servlet container starts accepting requests) when `spring.flyway.enabled=true` and `flyway-core` is on the classpath. The file naming convention `V{version}__{description}.sql` is mandatory — Flyway will ignore any file not matching this pattern. The composite index matches the PRD's performance note exactly: `(imo_number, year DESC, month DESC)`.

**Acceptance Criteria:**
- [ ] `V1__create_mti_scores_history.sql` creates table with all required columns and correct types
- [ ] Composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is created
- [ ] Unique constraint `uq_mti_scores_imo_year_month` on `(imo_number, year, month)` prevents duplicate entries
- [ ] Application starts without Flyway errors against a fresh PostgreSQL 15 database

**Tasks:**

**Task 1.2.a — Create Flyway migration** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Create the Flyway V1 migration. The SQL must: (1) Create table `mti_scores_history` with columns: `id BIGSERIAL PRIMARY KEY`, `imo_number VARCHAR(7) NOT NULL`, `year INTEGER NOT NULL`, `month INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12)`, `mti_score DECIMAL(5,2)` (nullable), `vessel_score DECIMAL(5,2)` (nullable), `reporting_score DECIMAL(5,2)` (nullable), `voyages_score DECIMAL(5,2)` (nullable), `emissions_score DECIMAL(5,2)` (nullable), `sanctions_score DECIMAL(5,2)` (nullable), `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`. (2) Create index: `CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC)`. (3) Add unique constraint: `ALTER TABLE mti_scores_history ADD CONSTRAINT uq_mti_scores_imo_year_month UNIQUE (imo_number, year, month)`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Data Layer

**Goal:** Implement the repository pattern for querying `mti_scores_history` with the three SQL query variants defined in the PRD.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Domain Model and RowMapper

**As a** developer **I want** a `MtiScoreRecord` Java record and a `MtiScoreRowMapper` **so that** repository results can be passed through the service layer as strongly typed objects with correct null handling for nullable score columns.

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` is a Java record with fields matching all table columns
- [ ] `MtiScoreRowMapper` reads nullable DECIMAL columns as `Double` using `rs.getObject(column, Double.class)` (returns null for SQL NULL)
- [ ] Timestamps read as `OffsetDateTime` and converted to `Instant`

**Tasks:**

**Task 2.1.a — Create MtiScoreRecord** — file: `source/src/main/java/com/example/mti/model/MtiScoreRecord.java`

Create `MtiScoreRecord` as a Java record (not a JPA entity) in package `com.example.mti.model`. Define record components in this order: `String imoNumber`, `int year`, `int month`, `Double mtiScore`, `Double vesselScore`, `Double reportingScore`, `Double voyagesScore`, `Double emissionsScore`, `Double sanctionsScore`, `java.time.Instant createdAt`, `java.time.Instant updatedAt`. Use `Double` (boxed) for score fields so they can hold `null`. This is a pure data carrier — no annotations, no methods beyond the auto-generated record accessors.

**Task 2.1.b — Create MtiScoreRowMapper** — file: `source/src/main/java/com/example/mti/repository/MtiScoreRowMapper.java`

Create class `MtiScoreRowMapper` in package `com.example.mti.repository`, annotated `@Component`, implementing `org.springframework.jdbc.core.RowMapper<MtiScoreRecord>`. In `mapRow(ResultSet rs, int rowNum) throws SQLException`: read `rs.getString("imo_number")` as `imoNumber`; read `rs.getInt("year")` as `year`; read `rs.getInt("month")` as `month`; for each nullable score column use `rs.getObject("mti_score", Double.class)` — this returns `null` when the SQL value is NULL, which is the correct behavior; for `created_at` and `updated_at`: read with `rs.getObject("created_at", java.time.OffsetDateTime.class)`, then call `.toInstant()` (wrap with null check: if result is null, assign null to the Instant). Return `new MtiScoreRecord(imoNumber, year, month, mtiScore, vesselScore, reportingScore, voyagesScore, emissionsScore, sanctionsScore, createdAt, updatedAt)`.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 2.2: MtiScoresRepository

**As a** developer **I want** a repository interface and JDBC implementation with the three SQL query variants from the PRD **so that** the service layer can retrieve scores without any knowledge of SQL or JDBC details.

**Background for implementer:** Spring JdbcTemplate's `query()` returns an empty `List` when no rows match — it does NOT throw an exception for zero results. We return `Optional` at the repository boundary so the service can use `.isEmpty()` cleanly without null checks. `queryForObject` is intentionally avoided: it throws `EmptyResultDataAccessException` on zero rows, requiring boilerplate try/catch. The `LIMIT 1` on all three queries prevents unexpected multi-row results even if the unique constraint is somehow violated.

**Acceptance Criteria:**
- [ ] Interface `MtiScoresRepository` declares three methods returning `Optional<MtiScoreRecord>`
- [ ] `MtiScoresRepositoryImpl` is annotated `@Repository` and autowired with `JdbcTemplate`
- [ ] All SQL uses `?` placeholders — never string concatenation
- [ ] Query SQL matches the PRD exactly: `ORDER BY year DESC, month DESC LIMIT 1` for latest; `ORDER BY month DESC LIMIT 1` for year filter

**Tasks:**

**Task 2.2.a — Create MtiScoresRepository interface** — file: `source/src/main/java/com/example/mti/repository/MtiScoresRepository.java`

Create interface `MtiScoresRepository` in package `com.example.mti.repository`. Declare three methods: (1) `Optional<MtiScoreRecord> findLatest(String imoNumber)` — returns the most recent record across all years and months for the given IMO. (2) `Optional<MtiScoreRecord> findLatestByYear(String imoNumber, int year)` — returns the most recent month's record within the specified year. (3) `Optional<MtiScoreRecord> findByYearAndMonth(String imoNumber, int year, int month)` — returns the record for the exact year and month combination. All return types use `java.util.Optional<MtiScoreRecord>`.

**Task 2.2.b — Create MtiScoresRepositoryImpl** — file: `source/src/main/java/com/example/mti/repository/MtiScoresRepositoryImpl.java`

Create class `MtiScoresRepositoryImpl` in `com.example.mti.repository`, annotated `@Repository`. Inject `org.springframework.jdbc.core.JdbcTemplate jdbcTemplate` and `MtiScoreRowMapper rowMapper` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresRepositoryImpl.class)`.

Implement `findLatest(String imoNumber)`: SQL is `SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1`; call `jdbcTemplate.query(sql, rowMapper, imoNumber)`; log at DEBUG: `log.debug("findLatest imo={}", imoNumber)`; return `Optional.ofNullable(results.isEmpty() ? null : results.get(0))`.

Implement `findLatestByYear(String imoNumber, int year)`: SQL is `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1`; log at DEBUG: `log.debug("findLatestByYear imo={} year={}", imoNumber, year)`; return the same Optional pattern.

Implement `findByYearAndMonth(String imoNumber, int year, int month)`: SQL is `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1`; log at DEBUG: `log.debug("findByYearAndMonth imo={} year={} month={}", imoNumber, year, month)`; return same Optional pattern. All three methods pass parameters as varargs to `jdbcTemplate.query()` — never via string concatenation.

**Complexity:** M | **Dependencies:** Story 2.1

---

### Epic 3: Service Layer

**Goal:** Implement all validation rules and business routing logic, mapping domain records to response DTOs.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: Error Codes and Custom Exception

**As a** developer **I want** an `ErrorCode` enum and `MtiApiException` class **so that** all error conditions are represented as typed, unchecked exceptions with the correct HTTP status codes and message titles baked in.

**Acceptance Criteria:**
- [ ] `ErrorCode` has entries ERR_101 through ERR_105 with correct title strings and `HttpStatus` values
- [ ] `MtiApiException` carries an `ErrorCode` and a detail message string
- [ ] `MtiApiException` extends `RuntimeException` (unchecked — no `throws` declarations needed on callers)

**Tasks:**

**Task 3.1.a — Create ErrorCode enum** — file: `source/src/main/java/com/example/mti/exception/ErrorCode.java`

Create enum `ErrorCode` in package `com.example.mti.exception`. Each constant stores three fields via constructor: `private final String code`, `private final String title`, `private final org.springframework.http.HttpStatus httpStatus`. Define constants: `ERR_101("ERR_101", "Resource Not Found", HttpStatus.NOT_FOUND)`, `ERR_102("ERR_102", "Invalid Parameters", HttpStatus.BAD_REQUEST)`, `ERR_103("ERR_103", "Invalid IMO Format", HttpStatus.BAD_REQUEST)`, `ERR_104("ERR_104", "Invalid Date Range", HttpStatus.BAD_REQUEST)`, `ERR_105("ERR_105", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR)`. Add public getters `getCode()` returning `String`, `getTitle()` returning `String`, `getHttpStatus()` returning `HttpStatus`.

**Task 3.1.b — Create MtiApiException** — file: `source/src/main/java/com/example/mti/exception/MtiApiException.java`

Create class `MtiApiException extends RuntimeException` in `com.example.mti.exception`. Fields: `private final ErrorCode errorCode`, `private final String detailMessage`. Constructor `public MtiApiException(ErrorCode errorCode, String detailMessage)` calls `super(detailMessage)` and assigns both fields. Add getters `getErrorCode()` returning `ErrorCode` and `getDetailMessage()` returning `String`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 3.2: Response DTOs and MtiScoresService

**As a** developer **I want** response DTO records and `MtiScoresService` to validate inputs, route to the correct repository method, and return a fully assembled `SuccessResponse` **so that** the controller layer is thin and all business rules are independently testable.

**Background for implementer:** The service receives the `requestId` generated by `RequestIdFilter` so it can embed it in the response `meta` field and in log statements. Validation happens in the service (not the controller) so it can be unit-tested without a web layer. The service throws `MtiApiException` for all error conditions — `GlobalExceptionHandler` converts these to HTTP responses, keeping the controller free of error-handling logic. The service returns `SuccessResponse` (not a domain record) so the controller only does `ResponseEntity.ok(response)`.

**Acceptance Criteria:**
- [ ] Throws `MtiApiException(ERR_103, "IMO number must be 7 digits")` when IMO does not match `^[0-9]{7}$`
- [ ] Throws `MtiApiException(ERR_102, "Month parameter requires year parameter to be specified")` when month non-null and year null
- [ ] Throws `MtiApiException(ERR_104, "Year must be between 2000 and 2100")` or `"Month must be between 1 and 12"` for out-of-range values
- [ ] Throws `MtiApiException(ERR_101, "No MTI scores found for IMO {imo}")` when repository returns empty
- [ ] Returns `SuccessResponse` with fully populated meta and data fields on success
- [ ] Logs INFO on entry; WARN on not-found or validation errors

**Tasks:**

**Task 3.2.a — Create DTO records** — files: `source/src/main/java/com/example/mti/dto/`

Create the following Java records in package `com.example.mti.dto`. Each field uses `@com.fasterxml.jackson.annotation.JsonProperty("snake_case_name")` to produce the PRD-specified JSON field names.

`MetaDto.java`: record with `@JsonProperty("request_id") String requestId` and `@JsonProperty("request_timestamp") String requestTimestamp`.

`ScoresDto.java`: record with `@JsonProperty("mti_score") Double mtiScore`, `@JsonProperty("vessel_score") Double vesselScore`, `@JsonProperty("reporting_score") Double reportingScore`, `@JsonProperty("voyages_score") Double voyagesScore`, `@JsonProperty("emissions_score") Double emissionsScore`, `@JsonProperty("sanctions_score") Double sanctionsScore`. Jackson serializes null `Double` fields as JSON `null` by default — no `@JsonInclude` annotation needed.

`MtiScoresMetadata.java`: record with `@JsonProperty("created_at") String createdAt` and `@JsonProperty("updated_at") String updatedAt`.

`MtiScoresData.java`: record with `@JsonProperty("imo_number") String imoNumber`, `@JsonProperty("year") int year`, `@JsonProperty("month") int month`, `@JsonProperty("scores") ScoresDto scores`, `@JsonProperty("metadata") MtiScoresMetadata metadata`.

`SuccessResponse.java`: record with `@JsonProperty("meta") MetaDto meta` and `@JsonProperty("data") MtiScoresData data`.

`ErrorResponseData.java`: record with `@JsonProperty("error_code") String errorCode`, `@JsonProperty("title") String title`, `@JsonProperty("message") String message`.

`ErrorResponse.java`: record with `@JsonProperty("meta") MetaDto meta` and `@JsonProperty("data") ErrorResponseData data`.

**Task 3.2.b — Create MtiScoresService** — file: `source/src/main/java/com/example/mti/service/MtiScoresService.java`

Create class `MtiScoresService` in `com.example.mti.service`, annotated `@Service`. Inject `MtiScoresRepository mtiScoresRepository` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class)`. Declare `private static final java.util.regex.Pattern IMO_PATTERN = Pattern.compile("^[0-9]{7}$")`.

Implement `public SuccessResponse getScores(String requestId, String imoNumber, Integer year, Integer month)`:

Step 1 — Validate IMO: if `!IMO_PATTERN.matcher(imoNumber).matches()`, log `log.warn("Invalid IMO format imo={} requestId={}", imoNumber, requestId)` and throw `new MtiApiException(ErrorCode.ERR_103, "IMO number must be 7 digits")`.

Step 2 — Validate month-without-year: if `month != null && year == null`, log `log.warn("Month without year requestId={}", requestId)` and throw `new MtiApiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`.

Step 3 — Validate ranges: if `year != null && (year < 2000 || year > 2100)`, throw `new MtiApiException(ErrorCode.ERR_104, "Year must be between 2000 and 2100")`; if `month != null && (month < 1 || month > 12)`, throw `new MtiApiException(ErrorCode.ERR_104, "Month must be between 1 and 12")`.

Step 4 — Log entry: `log.info("GetScores requestId={} imo={} year={} month={}", requestId, imoNumber, year, month)`.

Step 5 — Route: if `year == null`, call `mtiScoresRepository.findLatest(imoNumber)`; else if `month == null`, call `mtiScoresRepository.findLatestByYear(imoNumber, year)`; else call `mtiScoresRepository.findByYearAndMonth(imoNumber, year, month)`. Store in `Optional<MtiScoreRecord> result`.

Step 6 — Not found: if `result.isEmpty()`, log `log.warn("No MTI scores found imo={} year={} month={} requestId={}", imoNumber, year, month, requestId)` and throw `new MtiApiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`.

Step 7 — Map to response: from `result.get()` construct `ScoresDto(record.mtiScore(), record.vesselScore(), record.reportingScore(), record.voyagesScore(), record.emissionsScore(), record.sanctionsScore())`; construct `MtiScoresMetadata` with `record.createdAt() != null ? record.createdAt().toString() : null` and same for `updatedAt`; construct `MtiScoresData(record.imoNumber(), record.year(), record.month(), scores, metadata)`; construct `MetaDto(requestId, java.time.Instant.now().toString())`; return `new SuccessResponse(meta, data)`.

**Complexity:** M | **Dependencies:** Stories 2.2, 3.1, 3.2.a

---

### Epic 4: API Layer

**Goal:** Expose the HTTP endpoint, propagate request IDs, handle exceptions uniformly, and enforce rate limits via servlet filters.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 4.1: RequestIdFilter

**As a** developer **I want** a servlet filter that generates a UUID request ID per request, stores it as a request attribute, and adds it to MDC **so that** every log statement includes the request_id for distributed tracing and every response includes it in the `meta` envelope.

**Background for implementer:** `@Order(1)` ensures `RequestIdFilter` is the outermost filter — it must run before `RateLimitFilter` (`@Order(2)`) so the requestId is available in logs even for rate-limited requests. The UUID is stored in `HttpServletRequest` attribute `RequestIdFilter.REQUEST_ID_ATTR` (a public constant) so downstream code (controller, exception handler, rate limit filter) can read it without coupling to MDC. MDC must be cleared in `finally` to prevent the requestId from leaking to other requests served by the same thread in the pool.

**Acceptance Criteria:**
- [ ] Annotated `@Component` and `@Order(1)`
- [ ] Every request gets a fresh `UUID.randomUUID().toString()` stored under attribute key `"requestId"`
- [ ] MDC key `"requestId"` is set before `chain.doFilter` and removed in `finally`
- [ ] Logs DEBUG: `"Incoming request method={} uri={} requestId={}"`

**Tasks:**

**Task 4.1.a — Create RequestIdFilter** — file: `source/src/main/java/com/example/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` in `com.example.mti.filter` implementing `jakarta.servlet.Filter`. Annotate with `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"` and `public static final String MDC_REQUEST_ID_KEY = "requestId"`. Declare `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`.

In `doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException`: cast to `HttpServletRequest httpReq`; generate `String requestId = UUID.randomUUID().toString()`; call `httpReq.setAttribute(REQUEST_ID_ATTR, requestId)`; call `MDC.put(MDC_REQUEST_ID_KEY, requestId)`; log DEBUG: `log.debug("Incoming request method={} uri={} requestId={}", httpReq.getMethod(), httpReq.getRequestURI(), requestId)`; call `chain.doFilter(request, response)` inside `try`; in `finally` block call `MDC.remove(MDC_REQUEST_ID_KEY)`.

**Complexity:** S | **Dependencies:** Story 1.1

---

#### Story 4.2: GlobalExceptionHandler

**As a** developer **I want** a `@RestControllerAdvice` that converts `MtiApiException` and unexpected `Exception` into the standard `ErrorResponse` JSON format **so that** every error response has consistent `meta` and `data` fields matching the PRD error response schema.

**Background for implementer:** `@RestControllerAdvice` intercepts exceptions thrown from any `@RestController` in the application context. Reading `requestId` from `HttpServletRequest` attribute (set by `RequestIdFilter`) ensures the same UUID appears in both the error response and the log. The catch-all `@ExceptionHandler(Exception.class)` handler must log at ERROR with the full stack trace so database connection failures and NullPointerExceptions are diagnosable, while the client only sees the generic ERR_105 message without any stack trace.

**Acceptance Criteria:**
- [ ] `MtiApiException` → HTTP status from `errorCode.getHttpStatus()`, body `ErrorResponse` with correct `error_code`, `title`, `message`
- [ ] Unexpected `Exception` → HTTP 500, ERR_105, generic message `"An unexpected error occurred"`
- [ ] Both handlers log at appropriate level (WARN for known errors, ERROR for unexpected) with requestId included

**Tasks:**

**Task 4.2.a — Create GlobalExceptionHandler** — file: `source/src/main/java/com/example/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` in `com.example.mti.exception`, annotated `@RestControllerAdvice`. Declare `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)`.

Add method `public ResponseEntity<ErrorResponse> handleMtiApiException(MtiApiException ex, HttpServletRequest request)` annotated `@ExceptionHandler(MtiApiException.class)`: read `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if `requestId` is null set it to `"unknown"`; log WARN: `log.warn("MTI API error requestId={} code={} message={}", requestId, ex.getErrorCode().getCode(), ex.getDetailMessage())`; construct `MetaDto(requestId, Instant.now().toString())`; construct `ErrorResponseData(ex.getErrorCode().getCode(), ex.getErrorCode().getTitle(), ex.getDetailMessage())`; return `ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(new ErrorResponse(meta, data))`.

Add method `public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request)` annotated `@ExceptionHandler(Exception.class)`: read `requestId` from request attribute (default `"unknown"` if null); log ERROR: `log.error("Unexpected error requestId={} message={}", requestId, ex.getMessage(), ex)`; construct `ErrorResponseData(ErrorCode.ERR_105.getCode(), ErrorCode.ERR_105.getTitle(), "An unexpected error occurred")`; return `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(meta, data))`.

**Complexity:** S | **Dependencies:** Stories 3.1, 3.2.a, 4.1

---

#### Story 4.3: VesselController

**As a** developer **I want** `VesselController` to handle `GET /api/v1/vessels/{imo}/mti-scores` and delegate entirely to `MtiScoresService` **so that** the controller contains only HTTP wiring with no business logic or error handling.

**Acceptance Criteria:**
- [ ] Endpoint responds at exact path `GET /api/v1/vessels/{imo}/mti-scores`
- [ ] Returns `200 OK` with `SuccessResponse` body on success
- [ ] Does NOT catch any exceptions — lets `GlobalExceptionHandler` handle all error cases
- [ ] Logs INFO per request with requestId, imo, year, month

**Tasks:**

**Task 4.3.a — Create VesselController** — file: `source/src/main/java/com/example/mti/controller/VesselController.java`

Create class `VesselController` in `com.example.mti.controller`, annotated `@RestController` and `@RequestMapping("/api/v1")`. Inject `MtiScoresService mtiScoresService` via constructor. Declare `private static final Logger log = LoggerFactory.getLogger(VesselController.class)`.

Add method `public ResponseEntity<SuccessResponse> getMtiScores(@PathVariable String imo, @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, HttpServletRequest request)` annotated `@GetMapping("/vessels/{imo}/mti-scores")`. In the method body: read `String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if null assign `"unknown"`; log INFO: `log.info("GetMtiScores requestId={} imo={} year={} month={}", requestId, imo, year, month)`; call `SuccessResponse response = mtiScoresService.getScores(requestId, imo, year, month)`; return `ResponseEntity.ok(response)`. No try/catch blocks — all exceptions propagate to `GlobalExceptionHandler`.

**Complexity:** S | **Dependencies:** Stories 3.2, 4.1, 4.2

---

#### Story 4.4: Rate Limiting Filter

**As a** developer **I want** a servlet filter that limits each client IP to 100 requests per minute using Bucket4j **so that** the API is protected from abuse as required by the PRD security constraints.

**Background for implementer:** Bucket4j's token bucket algorithm grants 100 tokens per 60-second interval. A `ConcurrentHashMap<String, Bucket>` keyed by client IP stores per-IP state; `computeIfAbsent` ensures thread-safe bucket creation on first request from each IP. This in-memory approach is appropriate for MVP — state is lost on restart, which is acceptable for rate limiting. `@Order(2)` ensures it runs after `RequestIdFilter` so the requestId is in scope for log statements and the 429 response body. The 429 response body mirrors the error format from the PRD (using a literal JSON string since the Jackson `ObjectMapper` is not injected into filters to avoid circular dependencies).

**Acceptance Criteria:**
- [ ] Annotated `@Order(2)` — runs after `RequestIdFilter`
- [ ] Uses `ConcurrentHashMap<String, Bucket>` for per-IP bucket storage
- [ ] Bucket configured: 100 tokens capacity, refilled at 100 tokens per 60 seconds (interval refill)
- [ ] Returns HTTP 429 with JSON body when token exhausted
- [ ] Logs WARN on rate limit exceeded: includes client IP and requestId

**Tasks:**

**Task 4.4.a — Create RateLimitConfig** — file: `source/src/main/java/com/example/mti/config/RateLimitConfig.java`

Create class `RateLimitConfig` in `com.example.mti.config`, annotated `@Configuration`. Inject `@Value("${app.rate-limit.requests-per-minute:100}") private int requestsPerMinute`. Add `@Bean public java.util.function.Supplier<io.github.bucket4j.Bucket> bucketSupplier()` that returns a lambda: `() -> io.github.bucket4j.Bucket.builder().addLimit(io.github.bucket4j.Bandwidth.classic(requestsPerMinute, io.github.bucket4j.Refill.intervally(requestsPerMinute, java.time.Duration.ofMinutes(1)))).build()`. This supplier is called once per unique IP in `RateLimitFilter` to create a new independent bucket per client.

**Task 4.4.b — Create RateLimitFilter** — file: `source/src/main/java/com/example/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` in `com.example.mti.filter` implementing `jakarta.servlet.Filter`. Annotate with `@Component` and `@Order(2)`. Inject `java.util.function.Supplier<io.github.bucket4j.Bucket> bucketSupplier` via constructor. Declare `private final java.util.concurrent.ConcurrentHashMap<String, io.github.bucket4j.Bucket> buckets = new ConcurrentHashMap<>()`. Declare `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`.

In `doFilter`: get `String ip = request.getRemoteAddr()`; get `io.github.bucket4j.Bucket bucket = buckets.computeIfAbsent(ip, k -> bucketSupplier.get())`; call `io.github.bucket4j.ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1)`; if `probe.isConsumed()` call `chain.doFilter(request, response)` and return; otherwise: cast to `HttpServletResponse httpResp`; read `String requestId = (String) ((jakarta.servlet.http.HttpServletRequest) request).getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`; if null set `requestId = "unknown"`; log WARN: `log.warn("Rate limit exceeded ip={} requestId={}", ip, requestId)`; set `httpResp.setStatus(429)`; set `httpResp.setContentType("application/json")`; write response body: `httpResp.getWriter().write("{\"meta\":{\"request_id\":\"" + requestId + "\",\"request_timestamp\":\"" + java.time.Instant.now() + "\"},\"data\":{\"error_code\":\"RATE_LIMIT\",\"title\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Maximum 100 requests per minute.\"}}")`.

**Complexity:** M | **Dependencies:** Stories 4.1, 1.1.c

---

### Epic 5: Testing

**Goal:** Provide unit tests for all business logic branches and integration tests for the full HTTP request/response cycle.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 5.1: Unit Tests for MtiScoresService

**As a** developer **I want** unit tests for `MtiScoresService` that mock the repository **so that** all business logic branches (validation, routing, mapping) are covered without a database dependency.

**Acceptance Criteria:**
- [ ] Tests cover all error codes ERR_101 through ERR_104 (ERR_105 covered by integration test)
- [ ] Tests cover all three routing cases: no filters, year-only, year+month
- [ ] Tests verify exact `errorCode` enum value and `detailMessage` string on thrown exceptions
- [ ] Tests verify exact field values on `SuccessResponse` including nullable score fields

**Tasks:**

**Task 5.1.a — Create MtiScoresServiceTest** — file: `source/src/test/java/com/example/mti/service/MtiScoresServiceTest.java`

Create class `MtiScoresServiceTest` in `com.example.mti.service`, annotated `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoresRepository repository` and `@InjectMocks MtiScoresService service`.

Write the following test methods:

`getScores_invalidImo_throwsERR103()`: call `service.getScores("req-1", "123", null, null)`; assert `MtiApiException` thrown; assert `exception.getErrorCode() == ErrorCode.ERR_103`.

`getScores_monthWithoutYear_throwsERR102()`: call `service.getScores("req-2", "9123456", null, 6)`; assert `MtiApiException` with `errorCode == ErrorCode.ERR_102` and `detailMessage.equals("Month parameter requires year parameter to be specified")`.

`getScores_invalidYear_throwsERR104()`: call with `imoNumber="9123456"`, `year=1999`, `month=null`; assert ERR_104.

`getScores_invalidMonth_throwsERR104()`: call with `imoNumber="9123456"`, `year=2023`, `month=13`; assert ERR_104.

`getScores_imoNotFound_throwsERR101()`: stub `when(repository.findLatest("9999999")).thenReturn(Optional.empty())`; call `service.getScores("req-3", "9999999", null, null)`; assert `MtiApiException` with `errorCode == ErrorCode.ERR_101`.

`getScores_latest_returnsCorrectResponse()`: construct `MtiScoreRecord("9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:00:00Z"))`; stub `repository.findLatest("9123456")` to return it wrapped in `Optional.of`; call `service.getScores("req-4", "9123456", null, null)`; assert `response.data().imoNumber().equals("9123456")`; assert `response.data().year() == 2024`; assert `response.data().month() == 1`; assert `response.data().scores().mtiScore() == 85.50`; assert `response.data().scores().sanctionsScore() == 100.00`; assert `response.meta().requestId().equals("req-4")`.

`getScores_withYear_callsFindLatestByYear()`: stub `repository.findLatestByYear("9123456", 2023)` to return a valid record; call `service.getScores("req-5", "9123456", 2023, null)`; verify `repository.findLatestByYear("9123456", 2023)` was called exactly once; verify `repository.findLatest(any())` was never called.

`getScores_withYearAndMonth_callsFindByYearAndMonth()`: stub `repository.findByYearAndMonth("9123456", 2023, 6)` to return a valid record; verify it was called once with exact args `("9123456", 2023, 6)`.

`getScores_partialNullScores_returnsNullFields()`: construct `MtiScoreRecord("9999998", 2024, 3, null, 88.00, null, 79.00, null, 100.00, Instant.now(), Instant.now())`; assert `response.data().scores().mtiScore() == null`; assert `response.data().scores().vesselScore() == 88.00`; assert `response.data().scores().reportingScore() == null`.

**Complexity:** M | **Dependencies:** Story 3.2

---

#### Story 5.2: Integration Tests

**As a** developer **I want** end-to-end integration tests using Testcontainers PostgreSQL **so that** the full HTTP → Controller → Service → Repository → DB path is verified for all PRD acceptance criteria.

**Background for implementer:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` starts the full application context including Flyway migration. The Testcontainers JDBC URL `jdbc:tc:postgresql:15:///mtidb` in `application-test.yml` causes `ContainerDatabaseDriver` to automatically start a PostgreSQL 15 container — no `@Container` annotation is needed when using the TC JDBC URL approach. `@Sql` annotations on each test method insert seed rows before the test and delete them after, keeping tests independent. Use `TestRestTemplate` for HTTP calls since it is auto-configured for `RANDOM_PORT` and handles connection details.

**Acceptance Criteria:**
- [ ] AC1 (latest): `GET /api/v1/vessels/9123456/mti-scores` → 200, `data.mti_score == 85.50`, `data.year == 2024`
- [ ] AC2 (year filter): `GET ?year=2023` → 200, `data.month == 12` (latest in 2023)
- [ ] AC3 (year+month): `GET ?year=2023&month=6` → 200, `data.scores.mti_score == 75.00`
- [ ] AC4 (not found): IMO `9999999` → 404, `data.error_code == "ERR_101"`
- [ ] AC5 (invalid IMO): IMO `123` → 400, `data.error_code == "ERR_103"`
- [ ] AC6 (month without year): `?month=6` → 400, `data.error_code == "ERR_102"`
- [ ] AC7 (invalid month): `?year=2023&month=13` → 400, `data.error_code == "ERR_104"`
- [ ] AC8 (null scores): IMO `9999998` → 200, `data.scores.mti_score == null`, `data.scores.vessel_score == 88.0`

**Tasks:**

**Task 5.2.a — Create test application config** — file: `source/src/test/resources/application-test.yml`

Set `spring.datasource.url` to `jdbc:tc:postgresql:15:///mtidb`. Set `spring.datasource.driver-class-name` to `org.testcontainers.jdbc.ContainerDatabaseDriver`. Set `spring.flyway.enabled` to `true`. Set `app.rate-limit.requests-per-minute` to `10000`.

**Task 5.2.b — Create test seed SQL** — files: `source/src/test/resources/db/test-data.sql` and `source/src/test/resources/db/cleanup.sql`

Four seed rows and a cleanup DELETE — see full_plan.md Task 5.2.b for exact INSERT statements.

**Task 5.2.c — Create VesselControllerIntegrationTest** — file: `source/src/test/java/com/example/mti/controller/VesselControllerIntegrationTest.java`

Full integration test class covering AC1–AC8 — see full_plan.md Task 5.2.c for complete test method specifications.

**Complexity:** L | **Dependencies:** Stories 4.3, 5.2.a, 5.2.b

---

### Epic 6: Containerization

**Goal:** Package the application as a Docker image and provide a local docker-compose for development.
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 6.1: Dockerfile and docker-compose

**As a** developer **I want** a `Dockerfile` and `docker-compose.yml` **so that** the full API stack can be started locally with a single `docker-compose up` command.

**Acceptance Criteria:**
- [ ] `docker build -t mti-scores-api source/` completes without errors
- [ ] `docker-compose up` starts both API and PostgreSQL
- [ ] Health endpoint accessible at `http://localhost:8080/actuator/health`

**Tasks:**

**Task 6.1.a — Create Dockerfile** — file: `source/Dockerfile`

Multi-stage build: stage `build` uses `maven:3.9.6-eclipse-temurin-17`; stage `runtime` uses `eclipse-temurin:17-jre-jammy`. Final image copies `target/mti-scores-api-1.0.0-SNAPSHOT.jar` as `app.jar`, exposes port 8080.

**Task 6.1.b — Create docker-compose.yml** — file: `source/docker-compose.yml`

Services: `postgres` (postgres:15, healthcheck via pg_isready) and `mti-api` (build: ., depends_on postgres healthy, env DATABASE_URL/USERNAME/PASSWORD, port 8080).

**Complexity:** S | **Dependencies:** Story 1.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     string    Required   7-digit vessel IMO number; must match ^[0-9]{7}$

Query Parameters:
  year    integer   Optional   Calendar year; valid range 2000–2100
  month   integer   Optional   Month number 1–12; requires year to be present

Success Response — 200:
  meta.request_id, meta.request_timestamp, data.imo_number, data.year, data.month,
  data.scores.{mti,vessel,reporting,voyages,emissions,sanctions}_score (all number|null),
  data.metadata.{created,updated}_at

Error Response — 4XX / 5XX:
  meta.request_id, meta.request_timestamp, data.error_code, data.title, data.message

Error Code Reference:
  ERR_101   404   No scores found
  ERR_102   400   Month without year
  ERR_103   400   Invalid IMO format
  ERR_104   400   Invalid date range
  ERR_105   500   Internal server error
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 200ms; composite index `(imo_number, year DESC, month DESC)` |
| Logging | SLF4J + Logback; MDC `requestId`; INFO entry; WARN validation/not-found; ERROR unexpected |
| Security | Parameterized SQL; regex validation; no stack traces in responses |
| Rate Limiting | Bucket4j in-memory; 100 req/min per IP; HTTP 429 |
| Testing | ≥80% coverage; Mockito unit tests; Testcontainers integration tests |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `VesselController` | `MtiScoresService`, `RequestIdFilter.REQUEST_ID_ATTR` | Delegates logic; reads requestId attribute |
| `MtiScoresService` | `MtiScoresRepository`, `ErrorCode`, `MtiApiException` | Validation, routing, mapping |
| `MtiScoresRepositoryImpl` | `JdbcTemplate`, `MtiScoreRowMapper` | Parameterized SQL execution |
| `GlobalExceptionHandler` | `ErrorCode`, `MtiApiException`, DTOs, `RequestIdFilter.REQUEST_ID_ATTR` | Exception-to-HTTP mapping |
| `RateLimitFilter` | `bucketSupplier`, `RequestIdFilter.REQUEST_ID_ATTR` | Per-IP token buckets |
| `RequestIdFilter` | None | Foundation filter; `@Order(1)` |

---

### Backend Implementation Order

1. Story 1.1 → 1.2 → 2.1 → 2.2 → 3.1 → 3.2 → 4.1 → 4.2 → 4.3 → 4.4 → 5.1 → 5.2 → 6.1

> Stories 5.1 and 6.1 can be developed in parallel once Story 4.3 is complete.
