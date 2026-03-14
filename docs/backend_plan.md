## BACKEND IMPLEMENTATION PLAN

**Base package:** `com.maritime.mti` | **Group ID:** `com.maritime` | **Artifact ID:** `mti-scores-api`

### Overview

The backend is a single Spring Boot service that exposes one GET endpoint. The servlet filter chain handles request ID injection and rate limiting before the DispatcherServlet. The controller delegates entirely to `MtiScoresService` for validation and query strategy selection. `MtiScoresRepository` executes one of three parameterized JDBC queries and returns an `Optional<MtiScoreRecord>`. All business errors are expressed as `MtiException` (unchecked), caught and mapped to the correct HTTP status and JSON error body by `GlobalExceptionHandler`.

---

### Epic 1: Project Bootstrap

**Goal:** Establish a runnable Spring Boot 3 Maven project with database connectivity and schema migration
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Maven project setup

**As a** developer **I want** a bootstrapped Maven project **so that** I can compile and run the application locally.

**Background for implementer:** Spring Boot 3.x requires Java 17+ and uses the `jakarta.*` namespace (not `javax.*`). The `spring-boot-starter-validation` brings Hibernate Validator for future use. Bucket4j 8.x requires `com.github.ben-manes.caffeine:caffeine` as the local cache provider — without it, the `Caffeine.newBuilder()` call in `RateLimitConfig` will fail at runtime. `springdoc-openapi-starter-webmvc-ui:2.3.0` is compatible with Spring Boot 3.2; earlier 1.x versions of springdoc are NOT compatible. `jackson-datatype-jsr310` is included transitively by `spring-boot-starter-web` but is listed explicitly to make the `Instant` serialization dependency visible. The Testcontainers BOM is not used here — individual module versions are pinned to `1.19.3`.

**Acceptance Criteria:**
- [ ] `source/pom.xml` compiles with `mvn clean package -DskipTests`
- [ ] Application starts and connects to PostgreSQL
- [ ] `GET /actuator/health` returns `{"status":"UP"}`

**Tasks:**

**Task 1.1.a — Create pom.xml** — file: `source/pom.xml`

Create a Maven POM with `<groupId>com.maritime</groupId>`, `<artifactId>mti-scores-api</artifactId>`, `<version>1.0.0-SNAPSHOT</version>`, and parent `org.springframework.boot:spring-boot-starter-parent:3.2.0`. Set `<java.version>17</java.version>` in properties. Add the following compile-scope dependencies: `org.springframework.boot:spring-boot-starter-web` (embedded Tomcat + REST), `org.springframework.boot:spring-boot-starter-jdbc` (JdbcTemplate), `org.springframework.boot:spring-boot-starter-actuator` (health endpoint), `org.springframework.boot:spring-boot-starter-validation` (Hibernate Validator), `org.flywaydb:flyway-core` (schema migration), `com.github.ben-manes.caffeine:caffeine:3.1.8` (Caffeine cache), `com.bucket4j:bucket4j-core:8.10.1` (token bucket rate limiter), `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0` (OpenAPI UI), `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (Instant serialization). Add runtime-scope dependency: `org.postgresql:postgresql:42.7.1`. Add test-scope dependencies: `org.springframework.boot:spring-boot-starter-test`, `org.testcontainers:junit-jupiter:1.19.3`, `org.testcontainers:postgresql:1.19.3`. Include the `spring-boot-maven-plugin` plugin under `<build><plugins>` for fat JAR assembly.

**Task 1.1.b — Create MtiScoresApplication** — file: `source/src/main/java/com/maritime/mti/MtiScoresApplication.java`

Create class `MtiScoresApplication` annotated `@SpringBootApplication`. Define `public static void main(String[] args)` that calls `SpringApplication.run(MtiScoresApplication.class, args)`. This is the sole Spring Boot entry point; no additional configuration annotations are needed here.

**Task 1.1.c — Create application.yml** — file: `source/src/main/resources/application.yml`

Define the following configuration. Under `spring.datasource`: `url` value `${DATABASE_URL:jdbc:postgresql://localhost:5432/mti_scores}`, `username` value `${DATABASE_USER:mti_user}`, `password` value `${DATABASE_PASSWORD:mti_pass}`, `driver-class-name: org.postgresql.Driver`. Under `spring.flyway`: `enabled: true`, `locations: classpath:db/migration`. Under `management.endpoints.web.exposure.include`: `health`. Under `server`: `port: ${PORT:8080}`. Under `springdoc`: `api-docs.path: /api-docs`. Under `app.rate-limit`: `capacity: 100`, `refill-minutes: 1`. Under `spring.jackson.serialization`: `write-dates-as-timestamps: false` — this is critical; without it Jackson serializes `Instant` as a two-element array instead of an ISO 8601 string.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Database schema migration

**As a** developer **I want** the `mti_scores_history` table created on startup **so that** the repository layer can query it immediately.

**Background for implementer:** Flyway applies migrations in version order on application startup before the application context finishes loading. The index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is essential for all three query patterns in the PRD — each begins with `WHERE imo_number = ?` and orders by year/month descending; a missing index causes full table scans as data grows. `NUMERIC(5,2)` stores values like 85.50 exactly without floating-point error. The unique constraint `uq_mti_imo_year_month` prevents accidental duplicate records for the same vessel-period.

**Acceptance Criteria:**
- [ ] Table `mti_scores_history` exists with all 12 columns after first startup
- [ ] Index `idx_mti_scores_imo_year_month` exists on `(imo_number, year DESC, month DESC)`
- [ ] All six score columns are nullable
- [ ] Unique constraint `uq_mti_imo_year_month` exists on `(imo_number, year, month)`

**Tasks:**

**Task 1.2.a — Create Flyway migration V1** — file: `source/src/main/resources/db/migration/V1__create_mti_scores_history.sql`

Write a SQL migration that creates table `mti_scores_history` with the following columns in order: `id BIGSERIAL PRIMARY KEY`, `imo_number VARCHAR(7) NOT NULL`, `year INTEGER NOT NULL`, `month INTEGER NOT NULL`, `mti_score NUMERIC(5,2) NULL`, `vessel_score NUMERIC(5,2) NULL`, `reporting_score NUMERIC(5,2) NULL`, `voyages_score NUMERIC(5,2) NULL`, `emissions_score NUMERIC(5,2) NULL`, `sanctions_score NUMERIC(5,2) NULL`, `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`. After the table definition, add: a unique constraint `CONSTRAINT uq_mti_imo_year_month UNIQUE (imo_number, year, month)`; a check constraint `CONSTRAINT chk_month_range CHECK (month >= 1 AND month <= 12)`; a check constraint `CONSTRAINT chk_year_range CHECK (year >= 2000 AND year <= 2100)`. Then create index `CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC)`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Epic 2: Domain and Repository Layer

**Goal:** Represent a `mti_scores_history` row as a typed Java record and provide all three query strategies via JdbcTemplate
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: MtiScoreRecord domain model

**As a** developer **I want** a Java type that maps a `mti_scores_history` row **so that** the repository returns strongly typed results.

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` holds all 12 columns of `mti_scores_history`
- [ ] All six score components are `BigDecimal` and nullable (Java `null` for SQL NULL)
- [ ] `createdAt` and `updatedAt` are `java.time.Instant`

**Tasks:**

**Task 2.1.a — Create MtiScoreRecord** — file: `source/src/main/java/com/maritime/mti/model/MtiScoreRecord.java`

Create Java record `MtiScoreRecord` (Java 16+ `record` syntax) with components in this exact order: `Long id`, `String imoNumber`, `int year`, `int month`, `BigDecimal mtiScore`, `BigDecimal vesselScore`, `BigDecimal reportingScore`, `BigDecimal voyagesScore`, `BigDecimal emissionsScore`, `BigDecimal sanctionsScore`, `Instant createdAt`, `Instant updatedAt`. Import `java.math.BigDecimal` and `java.time.Instant`. No annotations needed — this is a pure data carrier with no Jackson or JPA dependencies.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 2.2: MtiScoresRepository

**As a** developer **I want** a repository that executes the three SQL query variants **so that** the service layer retrieves scores without containing SQL.

**Background for implementer:** `JdbcTemplate.queryForObject` throws `EmptyResultDataAccessException` (a subclass of `DataAccessException`) when zero rows are returned. The repository catches this specific exception and returns `Optional.empty()`, keeping "not found" semantics in the service layer where they belong. `rs.getBigDecimal("mti_score")` already returns Java `null` when the SQL column is NULL — no additional null check is needed. Using `rs.getTimestamp("created_at").toInstant()` converts a JDBC `Timestamp` to `java.time.Instant` correctly for `TIMESTAMP WITH TIME ZONE` columns in PostgreSQL. All three queries enumerate columns explicitly (not `SELECT *`) so the RowMapper is order-independent.

**Acceptance Criteria:**
- [ ] `findLatest(imoNumber)` returns the row with the highest `(year DESC, month DESC)` for that IMO
- [ ] `findLatestByYear(imoNumber, year)` returns the row with highest `month DESC` for that IMO+year
- [ ] `findByYearAndMonth(imoNumber, year, month)` returns the exact matching row
- [ ] All three methods return `Optional.empty()` when no rows match
- [ ] `ROW_MAPPER` maps all 12 columns including nullable score fields

**Tasks:**

**Task 2.2.a — Create MtiScoresRepository** — file: `source/src/main/java/com/maritime/mti/repository/MtiScoresRepository.java`

Create class `MtiScoresRepository` annotated `@Repository`. Add a constructor accepting `JdbcTemplate jdbcTemplate` and assigning it to `private final JdbcTemplate jdbcTemplate`. Declare `private static final String SELECT_COLUMNS = "SELECT id, imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at FROM mti_scores_history"`. Declare `private static final RowMapper<MtiScoreRecord> ROW_MAPPER` as a lambda `(rs, rowNum) -> new MtiScoreRecord(rs.getLong("id"), rs.getString("imo_number"), rs.getInt("year"), rs.getInt("month"), rs.getBigDecimal("mti_score"), rs.getBigDecimal("vessel_score"), rs.getBigDecimal("reporting_score"), rs.getBigDecimal("voyages_score"), rs.getBigDecimal("emissions_score"), rs.getBigDecimal("sanctions_score"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant())`. Implement three public methods, each returning `Optional<MtiScoreRecord>` and wrapping the `jdbcTemplate.queryForObject(sql, ROW_MAPPER, params)` call in try-catch for `org.springframework.dao.EmptyResultDataAccessException`, returning `Optional.empty()` on catch and `Optional.of(result)` on success: (1) `findLatest(String imoNumber)` — SQL: `SELECT_COLUMNS + " WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1"`, parameters: `imoNumber`; (2) `findLatestByYear(String imoNumber, int year)` — SQL: `SELECT_COLUMNS + " WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1"`, parameters: `imoNumber, year`; (3) `findByYearAndMonth(String imoNumber, int year, int month)` — SQL: `SELECT_COLUMNS + " WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1"`, parameters: `imoNumber, year, month`.

**Complexity:** M | **Dependencies:** Story 2.1

---

### Epic 3: Error Handling Infrastructure

**Goal:** Define all error codes as an enum and centralize exception-to-HTTP-response mapping in a `@RestControllerAdvice`
**Priority:** High | **Estimated Complexity:** M

---

#### Story 3.1: ErrorCode enum and MtiException

**As a** developer **I want** a typed error code system **so that** any layer can throw a single exception type that carries the correct HTTP status and error metadata.

**Background for implementer:** Encoding `httpStatus` inside the enum eliminates switch statements in the handler and makes the PRD error table the single source of truth. `MtiException` is an unchecked `RuntimeException` so it propagates naturally through Spring's DispatcherServlet and filter chain to the `@RestControllerAdvice` without requiring checked-exception declarations on every method signature.

**Acceptance Criteria:**
- [ ] `ErrorCode` enum has ERR_101 through ERR_105 with correct HTTP statuses and titles matching the PRD
- [ ] `MtiException(ErrorCode, String)` stores both fields and extends `RuntimeException`

**Tasks:**

**Task 3.1.a — Create ErrorCode enum** — file: `source/src/main/java/com/maritime/mti/exception/ErrorCode.java`

Create enum `ErrorCode` with five constants, each constructed with `(int httpStatus, String title)`: `ERR_101(404, "Resource Not Found")`, `ERR_102(400, "Invalid Parameters")`, `ERR_103(400, "Invalid IMO Format")`, `ERR_104(400, "Invalid Date Range")`, `ERR_105(500, "Internal Server Error")`. Add `private final int httpStatus` and `private final String title` fields, a constructor `ErrorCode(int httpStatus, String title)` assigning both, and public getters `getHttpStatus()` returning `int` and `getTitle()` returning `String`. The enum `name()` method (inherited from `Enum`) returns the string `"ERR_101"` etc., which is used directly as `error_code` in the JSON response body.

**Task 3.1.b — Create MtiException** — file: `source/src/main/java/com/maritime/mti/exception/MtiException.java`

Create class `MtiException` extending `RuntimeException` with `private final ErrorCode errorCode` and `private final String detailMessage` fields. Add constructor `MtiException(ErrorCode errorCode, String detailMessage)` that calls `super(detailMessage)` and assigns both fields. Add public getters `getErrorCode()` returning `ErrorCode` and `getDetailMessage()` returning `String`. This is the only exception type thrown by `MtiScoresService` for all business-rule violations.

**Complexity:** S | **Dependencies:** None

---

#### Story 3.2: GlobalExceptionHandler and error response DTOs

**As a** developer **I want** a centralized exception handler **so that** all errors return the PRD-specified JSON envelope without stack traces leaking to clients.

**Background for implementer:** `@RestControllerAdvice` intercepts exceptions after the DispatcherServlet resolves the handler but before the response is committed, meaning the `RequestIdFilter` has already set the `requestId` in `HttpServletRequest` attributes. The handler reads `(String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)` to populate `meta.request_id` in error responses, ensuring consistent correlation IDs across both success and error paths. `MethodArgumentTypeMismatchException` is thrown by Spring when a `@RequestParam Integer year` receives a non-numeric string like `?year=abc` — this maps to ERR_104 since it is an invalid date-range parameter.

**Acceptance Criteria:**
- [ ] `MtiException` → correct HTTP status from `errorCode.getHttpStatus()`, body with `error_code`, `title`, `message`
- [ ] `MethodArgumentTypeMismatchException` → 400 ERR_104
- [ ] Generic `Exception` → 500 ERR_105 with safe message (no stack trace exposed)
- [ ] All error responses include `meta.request_id` from request attribute `RequestIdFilter.REQUEST_ID_ATTR`

**Tasks:**

**Task 3.2.a — Create MetaDto** — file: `source/src/main/java/com/maritime/mti/dto/MetaDto.java`

Create Java record `MetaDto` with components `@JsonProperty("request_id") String requestId` and `@JsonProperty("request_timestamp") Instant requestTimestamp`. Import `com.fasterxml.jackson.annotation.JsonProperty` and `java.time.Instant`. Jackson serializes `Instant` as an ISO 8601 UTC string because `spring.jackson.serialization.write-dates-as-timestamps=false` is set in `application.yml`.

**Task 3.2.b — Create ErrorDataDto** — file: `source/src/main/java/com/maritime/mti/dto/ErrorDataDto.java`

Create Java record `ErrorDataDto` with components `@JsonProperty("error_code") String errorCode`, `@JsonProperty("title") String title`, `@JsonProperty("message") String message`. This record holds the `data` block of all error responses.

**Task 3.2.c — Create ErrorResponse** — file: `source/src/main/java/com/maritime/mti/dto/ErrorResponse.java`

Create Java record `ErrorResponse` with components `MetaDto meta` and `ErrorDataDto data`. This is the top-level error response body serialized to JSON for all 4xx and 5xx responses.

**Task 3.2.d — Create GlobalExceptionHandler** — file: `source/src/main/java/com/maritime/mti/exception/GlobalExceptionHandler.java`

Create class `GlobalExceptionHandler` annotated `@RestControllerAdvice`. Add `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class)` (import `org.slf4j.Logger` and `org.slf4j.LoggerFactory`). Implement three `@ExceptionHandler` methods, each accepting `HttpServletRequest request` as a second parameter: (1) `handleMtiException(MtiException ex, HttpServletRequest request)` returns `ResponseEntity<ErrorResponse>` — log at WARN: `log.warn("Client error requestId={} errorCode={} message={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), ex.getErrorCode().name(), ex.getDetailMessage())`; build `MetaDto meta = new MetaDto((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), Instant.now())`; build `ErrorDataDto data = new ErrorDataDto(ex.getErrorCode().name(), ex.getErrorCode().getTitle(), ex.getDetailMessage())`; return `ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(new ErrorResponse(meta, data))`; (2) `handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request)` returns `ResponseEntity<ErrorResponse>` — log at WARN: `log.warn("Type mismatch requestId={} param={} value={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), ex.getName(), ex.getValue())`; build and return ERR_104 / 400 with message `"Invalid value for parameter: " + ex.getName()`; (3) `handleGeneric(Exception ex, HttpServletRequest request)` returns `ResponseEntity<ErrorResponse>` — log at ERROR: `log.error("Unexpected error requestId={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), ex)`; build and return ERR_105 / 500 with message `"An internal server error occurred"`. Import `com.maritime.mti.filter.RequestIdFilter`, `java.time.Instant`, `org.springframework.web.method.annotation.MethodArgumentTypeMismatchException`.

**Complexity:** M | **Dependencies:** Stories 3.1, 4.1 (for `RequestIdFilter.REQUEST_ID_ATTR`)

---

### Epic 4: Request Pipeline Filters

**Goal:** Inject a UUID request ID for traceability and enforce per-IP rate limiting before requests reach the controller
**Priority:** High | **Estimated Complexity:** M

---

#### Story 4.1: RequestIdFilter

**As a** developer **I want** every request to carry a UUID **so that** all log lines and response envelopes can be correlated by a single request ID.

**Background for implementer:** `OncePerRequestFilter` guarantees the filter executes exactly once per HTTP request even in forward/include dispatch scenarios. MDC (Mapped Diagnostic Context) is SLF4J's thread-local log context; setting `requestId` in MDC makes it available to every log statement on that thread via `%X{requestId}` in the Logback pattern. MDC must be cleared in `finally` to prevent leakage across thread pool reuse in a servlet container. `REQUEST_ID_ATTR` is declared `public static final` on this class so the controller, exception handler, and rate limit filter can reference it via `RequestIdFilter.REQUEST_ID_ATTR` without string literals.

**Acceptance Criteria:**
- [ ] Every request has `requestId` set as both a `HttpServletRequest` attribute (key `"requestId"`) and an MDC entry (key `"requestId"`)
- [ ] MDC is cleared in `finally` after every request
- [ ] Filter executes before `RateLimitFilter` due to `@Order(1)`

**Tasks:**

**Task 4.1.a — Create RequestIdFilter** — file: `source/src/main/java/com/maritime/mti/filter/RequestIdFilter.java`

Create class `RequestIdFilter` extending `org.springframework.web.filter.OncePerRequestFilter`, annotated `@Component` and `@Order(1)`. Declare `public static final String REQUEST_ID_ATTR = "requestId"`. Add `private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class)`. Override `doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException`. Method body: (1) `String requestId = UUID.randomUUID().toString()`; (2) `request.setAttribute(REQUEST_ID_ATTR, requestId)`; (3) `MDC.put("requestId", requestId)`; (4) log at DEBUG: `log.debug("Assigned requestId={} method={} uri={}", requestId, request.getMethod(), request.getRequestURI())`; (5) wrap `filterChain.doFilter(request, response)` in try-finally where `finally` executes `MDC.remove("requestId")`. Import `org.slf4j.MDC`, `java.util.UUID`, `jakarta.servlet.FilterChain`, `jakarta.servlet.ServletException`, `jakarta.servlet.http.HttpServletRequest`, `jakarta.servlet.http.HttpServletResponse`.

**Complexity:** S | **Dependencies:** None

---

#### Story 4.2: RateLimitFilter

**As a** developer **I want** per-IP rate limiting at 100 requests per minute **so that** the API is protected from request flooding.

**Background for implementer:** Bucket4j implements the token-bucket algorithm. Each client IP gets its own `Bucket` instance stored in a Caffeine `Cache<String, Bucket>`. `RateLimitConfig` creates both the `Cache` and the shared `BucketConfiguration` as Spring beans so they can be injected. On rate limit exceeded, the filter writes the 429 response directly via `HttpServletResponse` rather than throwing an exception, because `@RestControllerAdvice` does not intercept exceptions thrown inside servlet filters — the filter executes outside the DispatcherServlet's exception handling.

**Acceptance Criteria:**
- [ ] Requests from an IP exceeding 100 per minute receive HTTP 429 with a JSON error body
- [ ] 429 body contains `error_code: "ERR_RATE_LIMIT"`, `title: "Too Many Requests"`
- [ ] Filter executes after `RequestIdFilter` due to `@Order(2)`
- [ ] Caffeine cache is bounded to 10,000 entries with 1-hour access expiry

**Tasks:**

**Task 4.2.a — Create RateLimitConfig** — file: `source/src/main/java/com/maritime/mti/config/RateLimitConfig.java`

Create class `RateLimitConfig` annotated `@Configuration`. Declare `@Value("${app.rate-limit.capacity:100}") private int capacity` and `@Value("${app.rate-limit.refill-minutes:1}") private int refillMinutes`. Define `@Bean` method `rateLimitCache()` returning `Cache<String, Bucket>` (from `com.github.ben-manes.caffeine.cache.Cache`) — build with `Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(1, TimeUnit.HOURS).build()`. Define `@Bean` method `bucketConfiguration()` returning `BucketConfiguration` — build with `BucketConfiguration.builder().addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(refillMinutes)))).build()`. Import `io.github.bucket4j.Bandwidth`, `io.github.bucket4j.BucketConfiguration`, `io.github.bucket4j.Refill`, `com.github.ben-manes.caffeine.cache.Cache`, `com.github.ben-manes.caffeine.cache.Caffeine`, `java.time.Duration`, `java.util.concurrent.TimeUnit`.

**Task 4.2.b — Create RateLimitFilter** — file: `source/src/main/java/com/maritime/mti/filter/RateLimitFilter.java`

Create class `RateLimitFilter` extending `OncePerRequestFilter`, annotated `@Component` and `@Order(2)`. Add `private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class)`. Constructor-inject `Cache<String, Bucket> rateLimitCache` and `BucketConfiguration bucketConfiguration`. Override `doFilterInternal` with body: (1) `String clientIp = request.getRemoteAddr()`; (2) `Bucket bucket = rateLimitCache.get(clientIp, key -> Bucket.builder().addLimit(bucketConfiguration.getBandwidths()[0]).build())`; (3) `ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1)`; (4) if `!probe.isConsumed()`: log at WARN `log.warn("Rate limit exceeded requestId={} ip={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), clientIp)`, call `response.setStatus(429)`, `response.setContentType("application/json")`, then write JSON body `{"meta":{"request_id":"` + request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR) + `","request_timestamp":"` + Instant.now().toString() + `"},"data":{"error_code":"ERR_RATE_LIMIT","title":"Too Many Requests","message":"Rate limit exceeded. Try again later."}}` via `response.getWriter().write(...)`, then `return`; (5) else call `filterChain.doFilter(request, response)`. Import `io.github.bucket4j.Bucket`, `io.github.bucket4j.BucketConfiguration`, `io.github.bucket4j.ConsumptionProbe`, `java.time.Instant`.

**Complexity:** M | **Dependencies:** Story 4.1

---

### Epic 5: Service and API Layer

**Goal:** Implement input validation, query strategy selection in the service, and the REST controller
**Priority:** High | **Estimated Complexity:** L

---

#### Story 5.1: Response DTOs

**As a** developer **I want** typed DTO classes for the success response **so that** Jackson serializes the correct snake_case JSON field names.

**Acceptance Criteria:**
- [ ] `MtiScoresResponse` wraps `MetaDto meta` and `VesselScoresData data`
- [ ] `ScoresDto` has six `BigDecimal` fields all annotated with PRD-exact snake_case `@JsonProperty` names
- [ ] Null `BigDecimal` score values serialize as JSON `null` (not omitted)

**Tasks:**

**Task 5.1.a — Create ScoresDto** — file: `source/src/main/java/com/maritime/mti/dto/ScoresDto.java`

Create Java record `ScoresDto` with six `BigDecimal` components (all nullable): `mtiScore`, `vesselScore`, `reportingScore`, `voyagesScore`, `emissionsScore`, `sanctionsScore`. Annotate each with `@JsonProperty` using the exact PRD names: `"mti_score"`, `"vessel_score"`, `"reporting_score"`, `"voyages_score"`, `"emissions_score"`, `"sanctions_score"` respectively. Do NOT annotate with `@JsonInclude(NON_NULL)` — the PRD explicitly requires null score fields to appear as `null` in the response body, not to be omitted.

**Task 5.1.b — Create RecordMetadataDto** — file: `source/src/main/java/com/maritime/mti/dto/RecordMetadataDto.java`

Create Java record `RecordMetadataDto` with components `@JsonProperty("created_at") Instant createdAt` and `@JsonProperty("updated_at") Instant updatedAt`. Import `java.time.Instant`.

**Task 5.1.c — Create VesselScoresData** — file: `source/src/main/java/com/maritime/mti/dto/VesselScoresData.java`

Create Java record `VesselScoresData` with components: `@JsonProperty("imo_number") String imoNumber`, `int year`, `int month`, `ScoresDto scores`, `RecordMetadataDto metadata`. The `year` and `month` fields do not require `@JsonProperty` annotations because their names already match the PRD JSON field names.

**Task 5.1.d — Create MtiScoresResponse** — file: `source/src/main/java/com/maritime/mti/dto/MtiScoresResponse.java`

Create Java record `MtiScoresResponse` with components `MetaDto meta` and `VesselScoresData data`. This is the top-level success response body.

**Complexity:** S | **Dependencies:** Story 3.2 (MetaDto)

---

#### Story 5.2: MtiScoresService

**As a** developer **I want** a service that validates all inputs and selects the correct query strategy **so that** the controller is thin and all business rules are in one place.

**Background for implementer:** Validation order is significant: IMO format is a path parameter that is always present and cheapest to check, so it runs first. Cross-parameter validation (month without year — ERR_102) runs second because it requires both query params to be inspected together. Year and month range validation (ERR_104) runs third. Only after all validations pass does the service hit the database. `DataAccessException` from `JdbcTemplate` wraps all JDBC-level failures; catching it at service level and rethrowing as `MtiException(ERR_105)` prevents infrastructure details from leaking upward and produces the correct HTTP 500 response via `GlobalExceptionHandler`.

**Acceptance Criteria:**
- [ ] IMO not matching `^[0-9]{7}$` → throws `MtiException(ErrorCode.ERR_103, "IMO number must be exactly 7 digits")`
- [ ] Month present but year absent → throws `MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`
- [ ] Year outside 2000–2100 → throws `MtiException(ErrorCode.ERR_104, "Year must be between 2000 and 2100")`
- [ ] Month outside 1–12 → throws `MtiException(ErrorCode.ERR_104, "Month must be between 1 and 12")`
- [ ] No DB row found → throws `MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`
- [ ] `DataAccessException` → throws `MtiException(ErrorCode.ERR_105, "Database error")`
- [ ] Returns `MtiScoreRecord` on success

**Tasks:**

**Task 5.2.a — Create MtiScoresService** — file: `source/src/main/java/com/maritime/mti/service/MtiScoresService.java`

Create class `MtiScoresService` annotated `@Service`. Add `private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class)` and `private static final Pattern IMO_PATTERN = Pattern.compile("^[0-9]{7}$")`. Constructor-inject `MtiScoresRepository mtiScoresRepository`. Implement public method `getScores(String imoNumber, Integer year, Integer month)` returning `MtiScoreRecord`. Body in order: (1) if `!IMO_PATTERN.matcher(imoNumber).matches()` throw `new MtiException(ErrorCode.ERR_103, "IMO number must be exactly 7 digits")`; (2) if `month != null && year == null` throw `new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified")`; (3) if `year != null && (year < 2000 || year > 2100)` throw `new MtiException(ErrorCode.ERR_104, "Year must be between 2000 and 2100")`; (4) if `month != null && (month < 1 || month > 12)` throw `new MtiException(ErrorCode.ERR_104, "Month must be between 1 and 12")`; (5) log at INFO: `log.info("Fetching MTI scores imoNumber={} year={} month={}", imoNumber, year, month)`; (6) wrap the following in try-catch `DataAccessException dae` — in catch: log at ERROR `log.error("Database error fetching MTI scores imoNumber={}", imoNumber, dae)`, then throw `new MtiException(ErrorCode.ERR_105, "Database error")`; (7) inside try: if `year == null` call `mtiScoresRepository.findLatest(imoNumber)`, else if `month == null` call `mtiScoresRepository.findLatestByYear(imoNumber, year)`, else call `mtiScoresRepository.findByYearAndMonth(imoNumber, year, month)` — assign result to `Optional<MtiScoreRecord> result`; (8) if `result.isEmpty()` log at WARN `log.warn("No MTI scores found imoNumber={} year={} month={}", imoNumber, year, month)` and throw `new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber)`; (9) return `result.get()`. Import `java.util.regex.Pattern`, `java.util.Optional`, `org.springframework.dao.DataAccessException`.

**Complexity:** M | **Dependencies:** Stories 2.2, 3.1

---

#### Story 5.3: VesselMtiScoresController

**As a** developer **I want** the REST controller to expose `GET /api/v1/vessels/{imo}/mti-scores` **so that** clients can retrieve MTI scores via HTTP.

**Background for implementer:** The controller is intentionally thin — all validation and business logic lives in `MtiScoresService`. The controller's only responsibilities are: extract parameters from the HTTP request, call the service, build the `meta` block from the `requestId` request attribute set by `RequestIdFilter`, assemble the response DTOs, and return a `ResponseEntity`. `@RequestParam(required = false)` causes Spring to pass `null` for absent `year` and `month` parameters, which is exactly what `MtiScoresService.getScores` expects.

**Acceptance Criteria:**
- [ ] `GET /api/v1/vessels/{imo}/mti-scores` returns 200 with correct `MtiScoresResponse` body
- [ ] `meta.request_id` in response matches `(String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)`
- [ ] `meta.request_timestamp` is a non-null ISO 8601 UTC string
- [ ] Null score fields in `ScoresDto` serialize as JSON `null`

**Tasks:**

**Task 5.3.a — Create VesselMtiScoresController** — file: `source/src/main/java/com/maritime/mti/controller/VesselMtiScoresController.java`

Create class `VesselMtiScoresController` annotated `@RestController` and `@RequestMapping("/api/v1")`. Add `private static final Logger log = LoggerFactory.getLogger(VesselMtiScoresController.class)`. Constructor-inject `MtiScoresService mtiScoresService`. Implement method `getMtiScores(@PathVariable String imo, @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, HttpServletRequest request)` returning `ResponseEntity<MtiScoresResponse>`, annotated `@GetMapping("/vessels/{imo}/mti-scores")`. Method body: (1) log at INFO: `log.info("GET /vessels/{}/mti-scores year={} month={} requestId={}", imo, year, month, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR))`; (2) `MtiScoreRecord record = mtiScoresService.getScores(imo, year, month)`; (3) `MetaDto meta = new MetaDto((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), Instant.now())`; (4) `ScoresDto scores = new ScoresDto(record.mtiScore(), record.vesselScore(), record.reportingScore(), record.voyagesScore(), record.emissionsScore(), record.sanctionsScore())`; (5) `RecordMetadataDto metadata = new RecordMetadataDto(record.createdAt(), record.updatedAt())`; (6) `VesselScoresData data = new VesselScoresData(record.imoNumber(), record.year(), record.month(), scores, metadata)`; (7) `return ResponseEntity.ok(new MtiScoresResponse(meta, data))`. Import `com.maritime.mti.filter.RequestIdFilter`, `java.time.Instant`, `jakarta.servlet.http.HttpServletRequest`.

**Complexity:** M | **Dependencies:** Stories 4.1, 5.1, 5.2

---

### Epic 6: Testing

**Goal:** Achieve ≥80% line coverage with unit tests for the service and integration tests for repository and controller
**Priority:** High | **Estimated Complexity:** L

---

#### Story 6.1: MtiScoresService unit tests

**As a** developer **I want** unit tests for all service validation paths and query-strategy branches **so that** every error code and business rule is verified without a database dependency.

**Acceptance Criteria:**
- [ ] Tests for all eight PRD acceptance criteria (AC1–AC8) plus all five error codes (ERR_101–ERR_105)
- [ ] `MtiScoresRepository` is mocked with Mockito
- [ ] All assertions check exact field values (not just non-null)

**Tasks:**

**Task 6.1.a — Create MtiScoresServiceTest** — file: `source/src/test/java/com/maritime/mti/service/MtiScoresServiceTest.java`

Create class `MtiScoresServiceTest` annotated `@ExtendWith(MockitoExtension.class)`. Declare `@Mock MtiScoresRepository mtiScoresRepository` and `@InjectMocks MtiScoresService mtiScoresService`. Define a static final helper record `MtiScoreRecord RECORD_2024_FEB` with values: id=1L, imoNumber="9123456", year=2024, month=2, mtiScore=new BigDecimal("86.00"), vesselScore=new BigDecimal("91.00"), reportingScore=new BigDecimal("89.00"), voyagesScore=new BigDecimal("83.00"), emissionsScore=new BigDecimal("88.00"), sanctionsScore=new BigDecimal("100.00"), createdAt=Instant.parse("2024-02-01T00:00:00Z"), updatedAt=Instant.parse("2024-02-01T00:00:00Z"). Define `MtiScoreRecord RECORD_2023_JUN` with: id=2L, imoNumber="9123456", year=2023, month=6, mtiScore=new BigDecimal("75.50"), vesselScore=new BigDecimal("80.00"), reportingScore=new BigDecimal("78.75"), voyagesScore=new BigDecimal("72.30"), emissionsScore=new BigDecimal("77.60"), sanctionsScore=new BigDecimal("95.00"), createdAt=Instant.parse("2023-06-01T00:00:00Z"), updatedAt=Instant.parse("2023-06-01T00:00:00Z"). Write the following `@Test` methods: (1) `getScores_latestNoFilters_returnsLatestRecord` — stub `mtiScoresRepository.findLatest("9123456")` to return `Optional.of(RECORD_2024_FEB)`; call `getScores("9123456", null, null)`; assert `result.year() == 2024` and `result.month() == 2`; (2) `getScores_yearFilter_returnsLatestForYear` — stub `findLatestByYear("9123456", 2023)` to return `Optional.of(RECORD_2023_JUN)`; call `getScores("9123456", 2023, null)`; assert `result.year() == 2023`, `result.month() == 6`; (3) `getScores_yearAndMonthFilter_returnsSpecificRecord` — stub `findByYearAndMonth("9123456", 2023, 6)` to return `Optional.of(RECORD_2023_JUN)`; assert `result.mtiScore().compareTo(new BigDecimal("75.50")) == 0`; (4) `getScores_imoNotFound_throwsErr101` — stub `findLatest("9999999")` to return `Optional.empty()`; assert `assertThrows(MtiException.class, () -> mtiScoresService.getScores("9999999", null, null))` and check `ex.getErrorCode() == ErrorCode.ERR_101`; (5) `getScores_invalidImoFormat_throwsErr103` — call `getScores("123", null, null)`; assert `MtiException` with `ErrorCode.ERR_103`; verify `verifyNoInteractions(mtiScoresRepository)`; (6) `getScores_monthWithoutYear_throwsErr102` — call `getScores("9123456", null, 6)`; assert `MtiException` with `ErrorCode.ERR_102`; (7) `getScores_invalidMonth13_throwsErr104` — call `getScores("9123456", 2023, 13)`; assert `MtiException` with `ErrorCode.ERR_104`; (8) `getScores_invalidYear1999_throwsErr104` — call `getScores("9123456", 1999, null)`; assert `MtiException` with `ErrorCode.ERR_104`; (9) `getScores_databaseException_throwsErr105` — stub `findLatest("9123456")` to throw `new DataAccessException("db error"){}` (anonymous subclass); assert `MtiException` with `ErrorCode.ERR_105`; (10) `getScores_nullScoreFields_returnsNullScores` — create a record with all score fields null; stub `findLatest("9123456")` to return it; assert `result.mtiScore() == null` and `result.vesselScore() == null`.

**Complexity:** M | **Dependencies:** Story 5.2

---

#### Story 6.2: MtiScoresRepository integration tests

**As a** developer **I want** repository tests against a real PostgreSQL container **so that** SQL query correctness is verified.

**Background for implementer:** Testcontainers starts a Docker PostgreSQL 15 container and Flyway runs the migration against it before tests execute. `@DynamicPropertySource` overrides `spring.datasource.url/username/password` with the container's dynamically assigned connection details. Test data is inserted in `@BeforeEach` using `jdbcTemplate.update` and cleaned up in `@AfterEach` with `jdbcTemplate.update("TRUNCATE TABLE mti_scores_history")` to guarantee isolation between tests. `BigDecimal` comparison must use `compareTo` (not `equals`) because `new BigDecimal("75.50")` and `new BigDecimal("75.5")` are equal by value but not by `equals` due to scale differences.

**Acceptance Criteria:**
- [ ] `findLatest("9123456")` returns year=2024 month=2 (row with highest year+month)
- [ ] `findLatestByYear("9123456", 2023)` returns month=6
- [ ] `findByYearAndMonth("9123456", 2023, 6)` returns mtiScore=75.50
- [ ] All three methods return `Optional.empty()` for IMO "9999999"
- [ ] Row 4 (IMO "1234567") returns null for `vesselScore`, `reportingScore`, `emissionsScore`

**Tasks:**

**Task 6.2.a — Create MtiScoresRepositoryTest** — file: `source/src/test/java/com/maritime/mti/repository/MtiScoresRepositoryTest.java`

Create class `MtiScoresRepositoryTest` annotated `@SpringBootTest` and `@Testcontainers`. Declare `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")`. Add static method `@DynamicPropertySource static void configureProperties(DynamicPropertyRegistry registry)` that calls: `registry.add("spring.datasource.url", postgres::getJdbcUrl)`, `registry.add("spring.datasource.username", postgres::getUsername)`, `registry.add("spring.datasource.password", postgres::getPassword)`. Autowire `MtiScoresRepository repository` and `JdbcTemplate jdbcTemplate`. In `@BeforeEach`, insert four rows using `jdbcTemplate.update("INSERT INTO mti_scores_history (imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", ...)` with values: row 1: "9123456", 2023, 6, 75.50, 80.00, 78.75, 72.30, 77.60, 95.00, Timestamp.from(Instant.parse("2023-06-01T00:00:00Z")), same; row 2: "9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00, Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")), same; row 3: "9123456", 2024, 2, 86.00, 91.00, 89.00, 83.00, 88.00, 100.00, Timestamp.from(Instant.parse("2024-02-01T00:00:00Z")), same; row 4: "1234567", 2024, 1, 70.00, null, null, 65.00, null, 100.00, Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")), same. In `@AfterEach`, execute `jdbcTemplate.update("TRUNCATE TABLE mti_scores_history")`. Write `@Test` methods for each acceptance criterion with exact assertions: `assertEquals(2024, result.year())`, `assertEquals(2, result.month())`, `assertTrue(result.mtiScore().compareTo(new BigDecimal("75.50")) == 0)`, `assertNull(result.vesselScore())`.

**Complexity:** M | **Dependencies:** Stories 2.2, 1.2

---

#### Story 6.3: Controller integration tests

**As a** developer **I want** end-to-end HTTP tests for all PRD acceptance criteria **so that** the full request pipeline (filter → controller → service → repository) is verified.

**Background for implementer:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate` starts a real embedded Tomcat, exercising the full filter chain including `RequestIdFilter` and `RateLimitFilter`. The same Testcontainers pattern is used for a real PostgreSQL container. The rate limit is raised to 1000 req/min in `application-test.yml` to prevent tests from hitting the 100 req/min limit. Jackson deserializes score values to `Double` when the target type is `Map.class`, so assertions must compare against `Double` (e.g., `assertEquals(86.0, data.get("mti_score"))`), not `BigDecimal`.

**Acceptance Criteria:**
- [ ] AC1: `GET /api/v1/vessels/9123456/mti-scores` → 200, `data.year=2024`, `data.month=2`, `data.scores.mti_score=86.0`
- [ ] AC2: `?year=2023` → 200, `data.year=2023`, `data.month=6`
- [ ] AC3: `?year=2023&month=6` → 200, `data.scores.mti_score=75.5`
- [ ] AC4: IMO 9999999 → 404, `data.error_code="ERR_101"`
- [ ] AC5: IMO "123" → 400, `data.error_code="ERR_103"`
- [ ] AC6: `?month=6` without year → 400, `data.error_code="ERR_102"`
- [ ] AC7: `?year=2023&month=13` → 400, `data.error_code="ERR_104"`
- [ ] AC8: IMO 1234567 → 200, `data.scores.vessel_score=null`
- [ ] Every response has non-null `meta.request_id` (UUID format) and non-null `meta.request_timestamp`

**Tasks:**

**Task 6.3.a — Create VesselMtiScoresControllerIntegrationTest** — file: `source/src/test/java/com/maritime/mti/controller/VesselMtiScoresControllerIntegrationTest.java`

Create class `VesselMtiScoresControllerIntegrationTest` annotated `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`, `@Testcontainers`, and `@ActiveProfiles("test")`. Use the same `@Container static PostgreSQLContainer<?>` and `@DynamicPropertySource` pattern as `MtiScoresRepositoryTest`. Autowire `@Autowired TestRestTemplate restTemplate`, `@LocalServerPort int port`, and `@Autowired JdbcTemplate jdbcTemplate`. Insert the same four seed rows in `@BeforeEach` and truncate in `@AfterEach`. Define helper `private String url(String path)` returning `"http://localhost:" + port + path`. For each acceptance criterion, call `restTemplate.getForEntity(url(path), Map.class)` and use `@SuppressWarnings("unchecked")` to cast the response body. Navigate the response map using `(Map<String, Object>) body.get("data")` and `(Map<String, Object>) data.get("scores")` for score assertions. Assert UUID format on `meta.request_id` with `assertTrue(((String) meta.get("request_id")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))`. For AC8, assert `assertNull(scores.get("vessel_score"))`.

**Task 6.3.b — Create application-test.yml** — file: `source/src/test/resources/application-test.yml`

Create YAML with: `app.rate-limit.capacity: 1000` (raises rate limit so tests are not throttled) and `logging.level.com.maritime.mti: DEBUG` (enables verbose logging during test runs). The `spring.datasource.*` values are overridden at runtime by `@DynamicPropertySource` and do not need to appear here.

**Complexity:** L | **Dependencies:** Stories 5.3, 6.2

---

### Epic 7: Containerization

**Goal:** Package the application in Docker and provide a docker-compose for full local stack
**Priority:** Medium | **Estimated Complexity:** S

---

#### Story 7.1: Dockerfile and docker-compose

**As a** developer **I want** a Docker image and docker-compose configuration **so that** I can run the full stack locally with one command.

**Acceptance Criteria:**
- [ ] `docker build -t mti-scores-api source/` produces a runnable image
- [ ] `docker-compose up` in `source/` starts PostgreSQL and the API; `GET /actuator/health` returns 200
- [ ] Multi-stage build keeps the final image to JRE-only (no Maven or JDK)

**Tasks:**

**Task 7.1.a — Create Dockerfile** — file: `source/Dockerfile`

Create a two-stage Dockerfile. Stage 1 named `build`: use base image `maven:3.9-eclipse-temurin-17-alpine`, set `WORKDIR /app`, copy `pom.xml` first (layer cache optimization), then copy `src/` directory, run `RUN mvn clean package -DskipTests`. Stage 2 named `runtime`: use base image `eclipse-temurin:17-jre-alpine`, set `WORKDIR /app`, copy `--from=build /app/target/mti-scores-api-1.0.0-SNAPSHOT.jar app.jar`, expose port `8080`, set `ENTRYPOINT ["java", "-jar", "app.jar"]`.

**Task 7.1.b — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create a docker-compose file (version `"3.9"`) with two services. Service `postgres`: image `postgres:15-alpine`, environment `POSTGRES_DB=mti_scores`, `POSTGRES_USER=mti_user`, `POSTGRES_PASSWORD=mti_pass`, ports `"5432:5432"`, volumes `postgres_data:/var/lib/postgresql/data`. Service `api`: `build: .`, depends_on `postgres`, environment `DATABASE_URL=jdbc:postgresql://postgres:5432/mti_scores`, `DATABASE_USER=mti_user`, `DATABASE_PASSWORD=mti_pass`, `PORT=8080`, ports `"8080:8080"`. Define top-level `volumes: postgres_data:`.

**Complexity:** S | **Dependencies:** Story 1.1

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     string    Required   7-digit IMO number; must match ^[0-9]{7}$

Query Parameters:
  year    integer   Optional   Filter by year; range 2000–2100 inclusive
  month   integer   Optional   Filter by month; range 1–12 inclusive; requires year

Request Headers:
  (none required — no authentication in v1)

Success Response — 200:
  meta.request_id                  string (UUID v4)
  meta.request_timestamp           string (ISO 8601 UTC)
  data.imo_number                  string
  data.year                        integer
  data.month                       integer (1–12)
  data.scores.mti_score            number | null
  data.scores.vessel_score         number | null
  data.scores.reporting_score      number | null
  data.scores.voyages_score        number | null
  data.scores.emissions_score      number | null
  data.scores.sanctions_score      number | null
  data.metadata.created_at         string (ISO 8601 UTC)
  data.metadata.updated_at         string (ISO 8601 UTC)

Error Response — 400 / 404 / 500:
  meta.request_id                  string (UUID v4)
  meta.request_timestamp           string (ISO 8601 UTC)
  data.error_code                  string
  data.title                       string
  data.message                     string

Error Code Reference:
  ERR_101   404   No MTI scores found for given IMO / year / month combination
  ERR_102   400   Month specified without year
  ERR_103   400   IMO number does not match ^[0-9]{7}$
  ERR_104   400   Year < 2000 or > 2100; or month < 1 or > 12
  ERR_105   500   Database connection or query failure
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | Index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` ensures single-row retrieval; p95 target < 50ms at 100 concurrent requests |
| Logging | SLF4J/Logback; MDC field `requestId` on all log lines; pattern `%d{ISO8601} [%X{requestId}] %-5level %logger{36} - %msg%n` |
| Metrics | Spring Actuator `/actuator/health` enabled; extend with `/actuator/metrics` for HTTP and JVM metrics |
| Security | No auth in v1; all JDBC queries use positional `?` parameters — no string concatenation in SQL; no stack traces in error responses |
| Rate Limiting | Token bucket (Bucket4j); 100 tokens per minute per client IP; greedy refill; 429 JSON response on exhaustion |
| Testing | ≥80% line coverage; unit tests with Mockito; repository integration with Testcontainers; controller end-to-end with TestRestTemplate |
| Health / Docs | `GET /actuator/health` → `{"status":"UP"}`; OpenAPI UI at `/swagger-ui.html`; raw spec at `/api-docs` |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `VesselMtiScoresController` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads requestId from request attribute to populate `meta.request_id` in success response |
| `GlobalExceptionHandler` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads requestId for `meta.request_id` in all error responses |
| `RateLimitFilter` | `RequestIdFilter.REQUEST_ID_ATTR` | Reads requestId for rate-limit exceeded log entry |
| `RateLimitFilter` | `RateLimitConfig` (Cache + BucketConfiguration beans) | Needs the Caffeine cache and bandwidth configuration injected via constructor |
| `RateLimitFilter` | `@Order(2)` after `RequestIdFilter @Order(1)` | Must run after RequestIdFilter so requestId is available in the log statement |
| `MtiScoresService` | `MtiScoresRepository` | Delegates all DB access to the repository |
| `MtiScoresService` | `ErrorCode` | Constructs `MtiException` with typed error codes |
| `GlobalExceptionHandler` | `ErrorCode` | Maps `MtiException.errorCode.getHttpStatus()` to HTTP response status |
| `VesselMtiScoresController` | `MtiScoresService` | Single call per request to `getScores(imo, year, month)` |
| `MtiScoresRepository` | `JdbcTemplate` (Spring bean) | JDBC query execution with parameterized statements |
| `MtiScoresRepository` | `MtiScoreRecord` | Return type of all three query methods |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — project skeleton must compile before any other code is written
2. **Story 1.2** — schema migration must exist before the repository can be coded or tested
3. **Story 2.1** — `MtiScoreRecord` must exist before the repository references it
4. **Story 2.2** — repository depends on domain model and migration
5. **Story 3.1** — `ErrorCode` and `MtiException` have no dependencies; unblocks service and handler
6. **Story 4.1** — `RequestIdFilter` and its `REQUEST_ID_ATTR` constant must exist before controller and handler reference it
7. **Story 3.2** — `GlobalExceptionHandler` imports `RequestIdFilter.REQUEST_ID_ATTR` (depends on Story 4.1)
8. **Story 4.2** — `RateLimitFilter` depends on `@Order(1)` being established and `REQUEST_ID_ATTR` available
9. **Story 5.1** — DTOs must exist before the controller can build them
10. **Story 5.2** — service depends on repository (2.2) and error codes (3.1)
11. **Story 5.3** — controller is final wiring of filter constant (4.1), DTOs (5.1), and service (5.2)
12. **Story 6.1** — unit tests for service; can start immediately after Story 5.2
13. **Story 6.2** — repository integration tests; can be developed in parallel with Story 6.1
14. **Story 6.3** — full-stack controller tests require the complete application wired (after Story 5.3)
15. **Story 7.1** — containerization is independent of Epic 6 and can proceed after Story 1.1

> Stories 6.1 and 6.2 can be developed in parallel. Story 7.1 is fully independent of Epic 6 and can proceed as soon as Story 1.1 is complete.
