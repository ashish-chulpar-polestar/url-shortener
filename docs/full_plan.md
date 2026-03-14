# AGILE IMPLEMENTATION PLAN
**Project:** MTI Scores API
**Type:** Greenfield Backend API

---

## EXECUTIVE SUMMARY

This project delivers a single REST endpoint that returns Maritime Transportation Indicator (MTI) scores for a vessel identified by its 7-digit IMO number, with optional filtering by year and/or month. The API is a greenfield Node.js/TypeScript service backed by PostgreSQL, following a layered architecture: middleware → controller → service → repository. The response envelope is standardized with a `meta` section (request_id, request_timestamp) and a `data` section for both success and error payloads. Non-functional requirements include rate limiting at 100 requests per minute per API key, parameterized SQL to prevent injection, and structured JSON logging with request correlation IDs.

---

## TECHNICAL ANALYSIS

### Recommended Stack (Greenfield)

| Layer | Technology | Justification |
|---|---|---|
| Language | TypeScript 5.x / Node.js 20 LTS | PRD references `uuid.v4()` (Node.js idiom); TypeScript adds compile-time safety for nullable score fields |
| Build | npm + tsc | Standard for TypeScript projects; lightweight pipeline with no external build tool |
| Framework | Express 4.x | Minimal HTTP framework; easy to layer middleware in explicit order; large ecosystem |
| Database | PostgreSQL 15 | Relational store matching PRD SQL queries exactly; supports NUMERIC(5,2) for score precision |
| DB Client | pg 8.x (node-postgres) | Raw SQL matches PRD query examples exactly; avoids ORM abstraction overhead |
| UUID | uuid 9.x | PRD specifies `uuid.v4()` for request_id generation |
| Logging | winston 3.x | Structured JSON logging; child loggers carry requestId as MDC-style metadata |
| Rate Limiting | express-rate-limit 7.x | Per-key token window rate limiting with custom keyGenerator |
| Testing | jest 29 + supertest 6 + testcontainers 10.x | Unit tests without HTTP; integration tests against real PostgreSQL container |
| Containerization | Docker + docker-compose | Standard local dev and deployment packaging |

### Project Structure

```
mti-scores-api/
└── source/
    ├── src/
    │   ├── server.ts
    │   ├── app.ts
    │   ├── config/
    │   │   ├── database.ts
    │   │   ├── env.ts
    │   │   └── logger.ts
    │   ├── constants/
    │   │   └── errorCodes.ts
    │   ├── controllers/
    │   │   └── MtiScoresController.ts
    │   ├── services/
    │   │   └── MtiScoresService.ts
    │   ├── repositories/
    │   │   └── MtiScoresRepository.ts
    │   ├── middleware/
    │   │   ├── requestIdMiddleware.ts
    │   │   ├── rateLimiterMiddleware.ts
    │   │   └── errorHandlerMiddleware.ts
    │   ├── dto/
    │   │   ├── MtiScoreResponseDto.ts
    │   │   └── ErrorResponseDto.ts
    │   ├── models/
    │   │   └── MtiScoreRecord.ts
    │   ├── types/
    │   │   └── custom.d.ts
    │   └── validators/
    │       └── mtiScoresValidator.ts
    ├── db/
    │   └── migrations/
    │       ├── V1__create_mti_scores_history.sql
    │       └── V2__seed_test_data.sql
    ├── test/
    │   ├── unit/
    │   │   ├── MtiScoresService.test.ts
    │   │   └── mtiScoresValidator.test.ts
    │   └── integration/
    │       └── mtiScores.integration.test.ts
    ├── Dockerfile
    ├── docker-compose.yml
    ├── package.json
    ├── tsconfig.json
    └── jest.config.ts
```

### Integration Points

- **PostgreSQL database**: Single table `mti_scores_history`; all reads via three parameterized SELECT variants
- **API consumers**: Authenticated via `X-API-Key` request header for rate limiting identity
- **No external services**: All data served from local database; no outbound HTTP calls

### Technical Constraints

- Rate limit: 100 requests per 60-second window per `X-API-Key` header value (falls back to `req.ip`)
- IMO validation: exactly 7 digits, regex `^[0-9]{7}$`
- Year range: 2000–2100 (per OpenAPI spec)
- Month range: 1–12; month without year is an explicit business error (ERR_102)
- NULL score fields must serialize as JSON `null`, not omitted
- All timestamps in ISO 8601 UTC format
- SQL injection prevention via pg positional parameters (`$1`, `$2`, `$3`)
- Composite index required on `(imo_number, year DESC, month DESC)` for query performance

---

## BACKEND IMPLEMENTATION PLAN

**Base directory:** `source/src` | **Package manager:** npm | **Entry point:** `source/src/server.ts`

### Overview

The backend is a single Express application with one route: `GET /api/v1/vessels/:imo/mti-scores`. Work is organized into four epics: infrastructure setup (config, logging, database pool, app bootstrap), data layer (migration, models, repository), API layer (middleware, validation, service, controller), and test coverage. The service layer selects the correct SQL query variant based on which optional parameters are present; the repository executes parameterized SQL and maps raw `pg` rows to typed TypeScript objects.

---

### Epic 1: Infrastructure & Project Setup

**Goal:** Create a runnable, testable Express server with database connectivity, structured logging, and centralized configuration.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 1.1: Project Scaffolding

**As a** developer **I want** a properly configured TypeScript/Node.js project **so that** the application compiles and tests execute without manual setup.

**Background for implementer:** The `source/` directory is the project root. All application source lives in `source/src/`. TypeScript compiles to `source/dist/`. `ts-node` enables running TypeScript directly in development. `ts-jest` allows Jest to consume TypeScript files without a pre-compile step. The `esModuleInterop: true` tsconfig flag is required for `uuid` and `winston` default imports. `testTimeout: 60000` is required in jest config to accommodate testcontainers PostgreSQL startup time.

**Acceptance Criteria:**
- [ ] `source/package.json` defines all production and dev dependencies with pinned minor versions
- [ ] `source/tsconfig.json` targets ES2020 with `strict: true` and `esModuleInterop: true`
- [ ] `source/jest.config.ts` configures ts-jest with 80% coverage threshold and 60s timeout
- [ ] `npm run build` compiles without errors
- [ ] `npm test` runs Jest and generates a coverage report

**Tasks:**

**Task 1.1.a — Create package.json** — file: `source/package.json`

Create `source/package.json` with `name` set to `mti-scores-api`, `version` to `1.0.0`, `main` to `dist/server.js`. Define scripts: `start` as `node dist/server.js`, `dev` as `ts-node src/server.ts`, `build` as `tsc`, `test` as `jest --coverage`. Production dependencies: `express` at `^4.18.2`, `pg` at `^8.11.3`, `uuid` at `^9.0.0`, `winston` at `^3.11.0`, `express-rate-limit` at `^7.1.5`. Dev dependencies: `typescript` at `^5.3.2`, `ts-node` at `^10.9.2`, `@types/express` at `^4.17.21`, `@types/pg` at `^8.10.9`, `@types/uuid` at `^9.0.7`, `@types/node` at `^20.10.0`, `jest` at `^29.7.0`, `ts-jest` at `^29.1.1`, `@types/jest` at `^29.5.10`, `supertest` at `^6.3.3`, `@types/supertest` at `^2.0.16`, `testcontainers` at `^10.4.0`.

**Task 1.1.b — Create tsconfig.json** — file: `source/tsconfig.json`

Create `source/tsconfig.json` with `compilerOptions`: `target` as `ES2020`, `module` as `commonjs`, `lib` as `["ES2020"]`, `outDir` as `./dist`, `rootDir` as `./src`, `strict` as `true`, `esModuleInterop` as `true`, `resolveJsonModule` as `true`, `skipLibCheck` as `true`, `forceConsistentCasingInFileNames` as `true`. Set `include` to `["src/**/*"]` and `exclude` to `["node_modules", "dist", "test"]`.

**Task 1.1.c — Create jest.config.ts** — file: `source/jest.config.ts`

Create `source/jest.config.ts` exporting a default Jest configuration object with `preset` as `ts-jest`, `testEnvironment` as `node`, `roots` as `["<rootDir>/test"]`, `testMatch` as `["**/*.test.ts"]`, `collectCoverageFrom` as `["src/**/*.ts", "!src/server.ts"]`, `coverageThreshold` as `{ global: { lines: 80 } }`, `testTimeout` as `60000`.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.2: Environment Configuration

**As a** developer **I want** a centralized environment variable loader **so that** all config is validated at startup and fails fast if required variables are missing.

**Background for implementer:** All modules read configuration from the exported `config` object in `env.ts` rather than `process.env` directly. This makes the configuration contract explicit and testable: tests can override `process.env` before the module is loaded via dynamic `import()`. The startup failure on missing `DATABASE_URL` prevents silent misconfiguration in deployed environments.

**Acceptance Criteria:**
- [ ] `source/src/config/env.ts` exports a typed `config` constant with all fields
- [ ] Missing `DATABASE_URL` throws `Error("Missing required environment variable: DATABASE_URL")` at module load time
- [ ] `PORT` defaults to `3000`, `RATE_LIMIT_WINDOW_MS` to `60000`, `RATE_LIMIT_MAX_REQUESTS` to `100`

**Tasks:**

**Task 1.2.a — Create env.ts** — file: `source/src/config/env.ts`

Create `source/src/config/env.ts` exporting a `const config` with fields: `port: number` from `process.env.PORT` defaulting to `3000` (parsed with `parseInt`); `databaseUrl: string` from `process.env.DATABASE_URL` — if absent, throw `new Error("Missing required environment variable: DATABASE_URL")`; `rateLimitWindowMs: number` from `process.env.RATE_LIMIT_WINDOW_MS` defaulting to `60000` (parsed with `parseInt`); `rateLimitMaxRequests: number` from `process.env.RATE_LIMIT_MAX_REQUESTS` defaulting to `100` (parsed with `parseInt`); `nodeEnv: string` from `process.env.NODE_ENV` defaulting to `'development'`.

**Complexity:** S | **Dependencies:** None

---

#### Story 1.3: Logger Setup

**As a** developer **I want** a structured JSON logger **so that** all log output carries consistent fields and can be parsed by log aggregation systems.

**Background for implementer:** Winston is the logger because it supports child loggers that inherit parent metadata — this is the mechanism used to attach `requestId` to all log lines emitted within a request context. In production (`NODE_ENV=production`) the format is JSON; in development it is colorized simple text for readability. The `createChildLogger` export is used in the request ID middleware and downstream classes.

**Acceptance Criteria:**
- [ ] `source/src/config/logger.ts` exports a `logger` winston.Logger instance
- [ ] Log level is `debug` in development, `info` in production
- [ ] Output is JSON in production, colorized simple in development
- [ ] `createChildLogger(requestId: string): winston.Logger` is exported and returns a child logger with `{ requestId }` metadata

**Tasks:**

**Task 1.3.a — Create logger.ts** — file: `source/src/config/logger.ts`

Create `source/src/config/logger.ts`. Import `winston` from `winston`. Import `config` from `./env`. Create `export const logger = winston.createLogger({ level: config.nodeEnv === 'production' ? 'info' : 'debug', format: config.nodeEnv === 'production' ? winston.format.json() : winston.format.combine(winston.format.colorize(), winston.format.simple()), transports: [new winston.transports.Console()] })`. Export named function `createChildLogger(requestId: string): winston.Logger` that returns `logger.child({ requestId })`.

**Complexity:** S | **Dependencies:** Story 1.2

---

#### Story 1.4: Database Connection Pool

**As a** developer **I want** a singleton PostgreSQL connection pool **so that** all queries share connections efficiently and pool configuration is centralized.

**Background for implementer:** `node-postgres` (`pg`) is used directly without an ORM because the PRD specifies exact SQL queries. The `Pool` is a module-level singleton — Node.js module caching ensures only one pool is ever created per process. The `connectionTimeoutMillis: 2000` setting causes queries to fail fast on pool exhaustion rather than queuing indefinitely, surfacing ERR_105 errors to the client promptly.

**Acceptance Criteria:**
- [ ] `source/src/config/database.ts` exports a `pool` Pool instance
- [ ] Pool uses `config.databaseUrl` as the connection string
- [ ] Pool has `max: 10`, `idleTimeoutMillis: 30000`, `connectionTimeoutMillis: 2000`
- [ ] Pool emits an INFO log on connect and an ERROR log on pool error

**Tasks:**

**Task 1.4.a — Create database.ts** — file: `source/src/config/database.ts`

Create `source/src/config/database.ts`. Import `Pool` from `pg`. Import `config` from `./env`. Import `logger` from `./logger`. Export `const pool = new Pool({ connectionString: config.databaseUrl, max: 10, idleTimeoutMillis: 30000, connectionTimeoutMillis: 2000 })`. Attach `pool.on('connect', () => logger.info('Database connection established'))`. Attach `pool.on('error', (err: Error) => logger.error('Unexpected database pool error', { error: err.message }))`.

**Complexity:** S | **Dependencies:** Stories 1.2, 1.3

---

#### Story 1.5: Application Bootstrap

**As a** developer **I want** an Express application factory and HTTP server entry point **so that** the app can be started in production and imported without binding a port in tests.

**Background for implementer:** Separating `app.ts` (Express app creation, middleware registration) from `server.ts` (HTTP server startup with `listen()`) is essential for supertest integration tests. Supertest creates its own ephemeral server from the `app` export; if `listen()` were called in `app.ts`, tests would bind to the configured port. Middleware registration order in `app.ts` must be: `express.json()` → `requestIdMiddleware` → `rateLimiterMiddleware` → router → `errorHandlerMiddleware`. The error handler must be last.

**Acceptance Criteria:**
- [ ] `source/src/app.ts` exports an Express `Application` without calling `listen()`
- [ ] `source/src/server.ts` imports `app` and calls `app.listen(config.port, callback)`
- [ ] Middleware order: json → requestId → rateLimiter → router → errorHandler
- [ ] Startup log: INFO `'MTI Scores API server started'` with fields `{ port, nodeEnv }`

**Tasks:**

**Task 1.5.a — Create app.ts** — file: `source/src/app.ts`

Create `source/src/app.ts`. Import `express` and type `Application` from `express`. Import `requestIdMiddleware` from `./middleware/requestIdMiddleware`. Import `rateLimiterMiddleware` from `./middleware/rateLimiterMiddleware`. Import `errorHandlerMiddleware` from `./middleware/errorHandlerMiddleware`. Import `mtiScoresRouter` from `./controllers/MtiScoresController`. Create `const app: Application = express()`. Register in order: `app.use(express.json())`, `app.use(requestIdMiddleware)`, `app.use(rateLimiterMiddleware)`, `app.use('/api/v1', mtiScoresRouter)`, `app.use(errorHandlerMiddleware)`. Export `app` as default.

**Task 1.5.b — Create server.ts** — file: `source/src/server.ts`

Create `source/src/server.ts`. Import `app` from `./app`. Import `config` from `./config/env`. Import `logger` from `./config/logger`. Call `app.listen(config.port, () => { logger.info('MTI Scores API server started', { port: config.port, nodeEnv: config.nodeEnv }) })`. Register `process.on('unhandledRejection', (reason) => { logger.error('Unhandled rejection', { reason }); process.exit(1) })`. Register `process.on('uncaughtException', (err) => { logger.error('Uncaught exception', { error: err.message, stack: err.stack }); process.exit(1) })`.

**Complexity:** S | **Dependencies:** Stories 1.2, 1.3, 1.4

---

### Epic 2: Data Layer

**Goal:** Define the database schema, implement the typed repository, and provide domain model interfaces.
**Priority:** High | **Estimated Complexity:** M

---

#### Story 2.1: Database Migration

**As a** developer **I want** SQL migration files that create and seed the `mti_scores_history` table **so that** the schema is version-controlled and test data is reproducible.

**Background for implementer:** The composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` directly supports all three SELECT query variants defined in the PRD: latest (ORDER BY year DESC, month DESC LIMIT 1), by year (WHERE year = ? ORDER BY month DESC LIMIT 1), and by year+month (WHERE year = ? AND month = ? LIMIT 1). PostgreSQL uses an index scan descending on year, then month. The `CHECK` constraints at the database level provide a secondary defence against bad data even if application validation is bypassed.

**Acceptance Criteria:**
- [ ] `V1__create_mti_scores_history.sql` creates the table with `CREATE TABLE IF NOT EXISTS`
- [ ] All six score columns are `NUMERIC(5,2)` and nullable
- [ ] `CHECK` constraints enforce `imo_number ~ '^[0-9]{7}$'`, `month BETWEEN 1 AND 12`, `year BETWEEN 2000 AND 2100`
- [ ] Composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is created
- [ ] `V2__seed_test_data.sql` inserts four seed rows covering the acceptance criteria scenarios

**Tasks:**

**Task 2.1.a — Create schema migration** — file: `source/db/migrations/V1__create_mti_scores_history.sql`

Create `source/db/migrations/V1__create_mti_scores_history.sql`. Use `CREATE TABLE IF NOT EXISTS mti_scores_history` with columns: `id BIGSERIAL PRIMARY KEY`, `imo_number VARCHAR(7) NOT NULL`, `year INTEGER NOT NULL`, `month INTEGER NOT NULL`, `mti_score NUMERIC(5,2)` (nullable), `vessel_score NUMERIC(5,2)` (nullable), `reporting_score NUMERIC(5,2)` (nullable), `voyages_score NUMERIC(5,2)` (nullable), `emissions_score NUMERIC(5,2)` (nullable), `sanctions_score NUMERIC(5,2)` (nullable), `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`. Add table-level constraints: `CONSTRAINT chk_imo_format CHECK (imo_number ~ '^[0-9]{7}$')`, `CONSTRAINT chk_month_range CHECK (month BETWEEN 1 AND 12)`, `CONSTRAINT chk_year_range CHECK (year BETWEEN 2000 AND 2100)`. After the CREATE TABLE statement, add: `CREATE INDEX IF NOT EXISTS idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC)`.

**Task 2.1.b — Create seed data migration** — file: `source/db/migrations/V2__seed_test_data.sql`

Create `source/db/migrations/V2__seed_test_data.sql`. Insert the following four rows into `mti_scores_history` (columns: `imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score`): Row 1: `'9123456', 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00`; Row 2: `'9123456', 2023, 12, 80.00, 85.00, 83.50, 78.00, 82.00, 95.00`; Row 3: `'9123456', 2023, 6, 75.50, 80.00, 78.25, 72.30, 77.60, 90.00`; Row 4: `'9999998', 2024, 3, NULL, NULL, NULL, NULL, NULL, NULL`.

**Complexity:** S | **Dependencies:** None

---

#### Story 2.2: Domain Models and DTOs

**As a** developer **I want** typed TypeScript interfaces for database rows and API responses **so that** the TypeScript compiler enforces contract correctness across all layers.

**Acceptance Criteria:**
- [ ] `MtiScoreRecord` interface represents a database row with nullable score fields as `number | null`
- [ ] `MtiScoreResponseDto`, `MetaDto`, `ScoresDto`, `MtiScoreDataDto` represent the success response shape
- [ ] `ErrorResponseDto`, `ErrorDataDto`, and `ErrorCodes` constant are defined in `ErrorResponseDto.ts`
- [ ] `ErrorCodes` maps ERR_101 through ERR_105 to `{ code, title, httpStatus }`

**Tasks:**

**Task 2.2.a — Create MtiScoreRecord model** — file: `source/src/models/MtiScoreRecord.ts`

Create `source/src/models/MtiScoreRecord.ts`. Export interface `MtiScoreRecord` with fields: `id: number`, `imo_number: string`, `year: number`, `month: number`, `mti_score: number | null`, `vessel_score: number | null`, `reporting_score: number | null`, `voyages_score: number | null`, `emissions_score: number | null`, `sanctions_score: number | null`, `created_at: Date`, `updated_at: Date`.

**Task 2.2.b — Create success response DTOs** — file: `source/src/dto/MtiScoreResponseDto.ts`

Create `source/src/dto/MtiScoreResponseDto.ts`. Export interface `MetaDto` with `request_id: string` and `request_timestamp: string`. Export interface `ScoresDto` with `mti_score: number | null`, `vessel_score: number | null`, `reporting_score: number | null`, `voyages_score: number | null`, `emissions_score: number | null`, `sanctions_score: number | null`. Export interface `MtiScoreDataDto` with `imo_number: string`, `year: number`, `month: number`, `scores: ScoresDto`, `metadata: { created_at: string; updated_at: string }`. Export interface `MtiScoreResponseDto` with `meta: MetaDto` and `data: MtiScoreDataDto`.

**Task 2.2.c — Create error DTO and error codes** — file: `source/src/dto/ErrorResponseDto.ts`

Create `source/src/dto/ErrorResponseDto.ts`. Import `MetaDto` from `./MtiScoreResponseDto`. Export interface `ErrorDataDto` with `error_code: string`, `title: string`, `message: string`. Export interface `ErrorResponseDto` with `meta: MetaDto` and `data: ErrorDataDto`. Export `const ErrorCodes` (not an enum, to allow runtime key lookup) as: `{ ERR_101: { code: 'ERR_101', title: 'Resource Not Found', httpStatus: 404 }, ERR_102: { code: 'ERR_102', title: 'Invalid Parameters', httpStatus: 400 }, ERR_103: { code: 'ERR_103', title: 'Invalid IMO Format', httpStatus: 400 }, ERR_104: { code: 'ERR_104', title: 'Invalid Date Range', httpStatus: 400 }, ERR_105: { code: 'ERR_105', title: 'Internal Server Error', httpStatus: 500 } }`.

**Complexity:** S | **Dependencies:** None

---

#### Story 2.3: MTI Scores Repository

**As a** developer **I want** a repository class that executes all database queries **so that** the service layer has no SQL knowledge and all queries use parameterized values.

**Background for implementer:** `node-postgres` returns `NUMERIC` columns as JavaScript strings by default (this is the pg library's design — it does not cast numeric types automatically). The `mapRow` private method must call `parseFloat()` on each score column value when it is non-null. Failure to do this would cause the score fields to serialize as strings in JSON rather than numbers, violating the API contract. The `id`, `year`, and `month` columns are `INTEGER`/`BIGSERIAL` and must be cast with `parseInt()`.

**Acceptance Criteria:**
- [ ] `MtiScoresRepository` class exported from `source/src/repositories/MtiScoresRepository.ts`
- [ ] `findLatest(imoNumber: string): Promise<MtiScoreRecord | null>` uses query: `SELECT * FROM mti_scores_history WHERE imo_number = $1 ORDER BY year DESC, month DESC LIMIT 1`
- [ ] `findByYear(imoNumber: string, year: number): Promise<MtiScoreRecord | null>` uses query: `SELECT * FROM mti_scores_history WHERE imo_number = $1 AND year = $2 ORDER BY month DESC LIMIT 1`
- [ ] `findByYearAndMonth(imoNumber: string, year: number, month: number): Promise<MtiScoreRecord | null>` uses query: `SELECT * FROM mti_scores_history WHERE imo_number = $1 AND year = $2 AND month = $3 LIMIT 1`
- [ ] Returns `null` when `result.rows.length === 0`
- [ ] `parseFloat()` applied to all six nullable score columns in `mapRow`
- [ ] DEBUG log on each query call with query parameters

**Tasks:**

**Task 2.3.a — Create MtiScoresRepository** — file: `source/src/repositories/MtiScoresRepository.ts`

Create `source/src/repositories/MtiScoresRepository.ts`. Import `Pool` from `pg`. Import `MtiScoreRecord` from `../models/MtiScoreRecord`. Import `logger` from `../config/logger`. Export class `MtiScoresRepository` with constructor `constructor(private readonly pool: Pool)`. Add `private static readonly log = logger.child({ class: 'MtiScoresRepository' })`.

Implement `async findLatest(imoNumber: string): Promise<MtiScoreRecord | null>`: call `MtiScoresRepository.log.debug('Executing findLatest', { imoNumber })`, execute `this.pool.query('SELECT * FROM mti_scores_history WHERE imo_number = $1 ORDER BY year DESC, month DESC LIMIT 1', [imoNumber])`, return `result.rows.length > 0 ? this.mapRow(result.rows[0]) : null`.

Implement `async findByYear(imoNumber: string, year: number): Promise<MtiScoreRecord | null>`: call `MtiScoresRepository.log.debug('Executing findByYear', { imoNumber, year })`, execute query with `[imoNumber, year]`.

Implement `async findByYearAndMonth(imoNumber: string, year: number, month: number): Promise<MtiScoreRecord | null>`: call `MtiScoresRepository.log.debug('Executing findByYearAndMonth', { imoNumber, year, month })`, execute query with `[imoNumber, year, month]`.

Implement `private mapRow(row: any): MtiScoreRecord` returning `{ id: parseInt(row.id), imo_number: row.imo_number, year: parseInt(row.year), month: parseInt(row.month), mti_score: row.mti_score !== null ? parseFloat(row.mti_score) : null, vessel_score: row.vessel_score !== null ? parseFloat(row.vessel_score) : null, reporting_score: row.reporting_score !== null ? parseFloat(row.reporting_score) : null, voyages_score: row.voyages_score !== null ? parseFloat(row.voyages_score) : null, emissions_score: row.emissions_score !== null ? parseFloat(row.emissions_score) : null, sanctions_score: row.sanctions_score !== null ? parseFloat(row.sanctions_score) : null, created_at: new Date(row.created_at), updated_at: new Date(row.updated_at) }`.

**Complexity:** M | **Dependencies:** Stories 1.3, 1.4, 2.1, 2.2

---

### Epic 3: API Layer

**Goal:** Implement all HTTP-layer concerns: request ID correlation, input validation, rate limiting, business logic, controller, and error handling.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 3.1: Request ID Middleware

**As a** developer **I want** middleware that attaches a UUID v4 request_id to every request **so that** all log lines and responses carry a correlation ID for distributed tracing.

**Background for implementer:** Express's `Request` type does not have a `requestId` field by default. TypeScript requires extending it via declaration merging in a `.d.ts` file placed in `source/src/types/`. The same UUID is also stored in `res.locals.requestId` because the error handler middleware only has access to `res.locals`, not the original `req`, to retrieve it when composing error envelopes.

**Acceptance Criteria:**
- [ ] `source/src/types/custom.d.ts` augments `Express.Request` with `requestId: string`
- [ ] `requestIdMiddleware` sets both `req.requestId` and `res.locals.requestId` to the same `uuid.v4()` value
- [ ] DEBUG log: `'Incoming request'` with fields `{ requestId, method, path }`

**Tasks:**

**Task 3.1.a — Extend Express Request type** — file: `source/src/types/custom.d.ts`

Create `source/src/types/custom.d.ts`. Declare `global { namespace Express { interface Request { requestId: string } } }`. This file has no imports; it is a pure ambient declaration that makes `req.requestId` available without type error across the entire codebase.

**Task 3.1.b — Create requestIdMiddleware** — file: `source/src/middleware/requestIdMiddleware.ts`

Create `source/src/middleware/requestIdMiddleware.ts`. Import `Request`, `Response`, `NextFunction` from `express`. Import `v4 as uuidv4` from `uuid`. Import `logger` from `../config/logger`. Export named function `requestIdMiddleware(req: Request, res: Response, next: NextFunction): void`. Body: `const requestId = uuidv4()`, `req.requestId = requestId`, `res.locals.requestId = requestId`, `logger.debug('Incoming request', { requestId, method: req.method, path: req.path })`, `next()`.

**Complexity:** S | **Dependencies:** Story 1.3

---

#### Story 3.2: Input Validation

**As a** developer **I want** a pure validation function that checks all path and query parameters **so that** invalid input is rejected before reaching the service layer with the correct error code.

**Background for implementer:** Validation is a pure function (not Express middleware) so it can be unit-tested without an HTTP context. The function returns a discriminated union type: `{ valid: true }` or `{ valid: false; errorCode: string; message: string }`. Validation order matters: IMO format is checked first (ERR_103), then the month-without-year rule (ERR_102), then year range (ERR_104), then month range (ERR_104). This order matches the most likely input errors.

**Acceptance Criteria:**
- [ ] `validateMtiScoresRequest` exported from `source/src/validators/mtiScoresValidator.ts`
- [ ] IMO not matching `^[0-9]{7}$` → `errorCode: 'ERR_103'`, message `'IMO number must be exactly 7 digits'`
- [ ] Month present without year → `errorCode: 'ERR_102'`, message `'Month parameter requires year parameter to be specified'`
- [ ] Year outside 2000–2100 → `errorCode: 'ERR_104'`, message `'Year must be between 2000 and 2100'`
- [ ] Month outside 1–12 → `errorCode: 'ERR_104'`, message `'Month must be between 1 and 12'`
- [ ] Valid input → `{ valid: true }`

**Tasks:**

**Task 3.2.a — Create mtiScoresValidator** — file: `source/src/validators/mtiScoresValidator.ts`

Create `source/src/validators/mtiScoresValidator.ts`. Define `const IMO_REGEX = /^[0-9]{7}$/`. Export interface `ValidationParams` with `imo: string`, `year: number | undefined`, `month: number | undefined`. Export type `ValidationResult = { valid: true } | { valid: false; errorCode: string; message: string }`. Export function `validateMtiScoresRequest(params: ValidationParams): ValidationResult`. Implementation order: (1) if `!IMO_REGEX.test(params.imo)` return `{ valid: false, errorCode: 'ERR_103', message: 'IMO number must be exactly 7 digits' }`; (2) if `params.month !== undefined && params.year === undefined` return `{ valid: false, errorCode: 'ERR_102', message: 'Month parameter requires year parameter to be specified' }`; (3) if `params.year !== undefined && (params.year < 2000 || params.year > 2100)` return `{ valid: false, errorCode: 'ERR_104', message: 'Year must be between 2000 and 2100' }`; (4) if `params.month !== undefined && (params.month < 1 || params.month > 12)` return `{ valid: false, errorCode: 'ERR_104', message: 'Month must be between 1 and 12' }`; (5) return `{ valid: true }`.

**Complexity:** S | **Dependencies:** Story 2.2

---

#### Story 3.3: Rate Limiter Middleware

**As a** developer **I want** a rate limiter that caps each API key at 100 requests per minute **so that** the API is protected against abusive request volumes.

**Background for implementer:** `express-rate-limit` uses an in-memory store by default (sufficient for single-instance deployments). The `keyGenerator` reads the `X-API-Key` header; if absent it falls back to `req.ip` so unauthenticated requests are still rate-limited. The `handler` overrides the default plain-text 429 response with the API's standard JSON error envelope. `ERR_429` is used as the error code because none of ERR_101–ERR_105 represents rate limiting.

**Acceptance Criteria:**
- [ ] `rateLimiterMiddleware` exported from `source/src/middleware/rateLimiterMiddleware.ts`
- [ ] Window: `config.rateLimitWindowMs` ms; max: `config.rateLimitMaxRequests` requests
- [ ] Key: `X-API-Key` header value, falling back to `req.ip || 'unknown'`
- [ ] 429 response body: `{ meta: { request_id, request_timestamp }, data: { error_code: 'ERR_429', title: 'Too Many Requests', message: 'Rate limit exceeded. Maximum 100 requests per minute.' } }`

**Tasks:**

**Task 3.3.a — Create rateLimiterMiddleware** — file: `source/src/middleware/rateLimiterMiddleware.ts`

Create `source/src/middleware/rateLimiterMiddleware.ts`. Import `rateLimit` from `express-rate-limit`. Import `Request`, `Response` from `express`. Import `config` from `../config/env`. Export `const rateLimiterMiddleware = rateLimit({ windowMs: config.rateLimitWindowMs, max: config.rateLimitMaxRequests, standardHeaders: true, legacyHeaders: false, keyGenerator: (req: Request): string => (req.headers['x-api-key'] as string) || req.ip || 'unknown', handler: (req: Request, res: Response): void => { const requestId = (res.locals.requestId as string) || 'unknown'; res.status(429).json({ meta: { request_id: requestId, request_timestamp: new Date().toISOString() }, data: { error_code: 'ERR_429', title: 'Too Many Requests', message: 'Rate limit exceeded. Maximum 100 requests per minute.' } }) } })`.

**Complexity:** S | **Dependencies:** Stories 1.2, 3.1

---

#### Story 3.4: Error Handler and Service Layer

**As a** developer **I want** a typed `ApiError` class and a service layer that encapsulates business logic **so that** the controller stays thin and errors propagate cleanly to a centralized handler.

**Background for implementer:** `ApiError` is defined in `errorHandlerMiddleware.ts` (co-located with the handler) so the handler can use `instanceof ApiError` without a circular import. The service receives already-validated parameters — it never re-validates. It selects the repository method based on which optional parameters are defined. The `request_id` and `request_timestamp` are injected by the controller, not the service; the service returns only `MtiScoreDataDto` (the `data` portion). On any database error that is not already an `ApiError`, the service wraps it in ERR_105 to prevent stack traces leaking to clients.

**Acceptance Criteria:**
- [ ] `ApiError` class exported from `source/src/middleware/errorHandlerMiddleware.ts` with `errorCode`, `message`, `httpStatus`
- [ ] `errorHandlerMiddleware` is an Express 4-argument error handler: `(err, req, res, next)`
- [ ] `ApiError` instances log at WARN, unknown errors log at ERROR with stack
- [ ] `MtiScoresService.getMtiScores(imoNumber, year?, month?)` selects correct repository method
- [ ] ERR_101 thrown when repository returns `null`; ERR_105 thrown on uncaught database exceptions
- [ ] INFO log on entry: `'Getting MTI scores'` with `{ imoNumber, year, month }`
- [ ] WARN log on not found: `'No MTI scores found'` with `{ imoNumber, year, month }`
- [ ] ERROR log on DB failure: `'Database error in getMtiScores'` with `{ imoNumber, error }`

**Tasks:**

**Task 3.4.a — Create ApiError and errorHandlerMiddleware** — file: `source/src/middleware/errorHandlerMiddleware.ts`

Create `source/src/middleware/errorHandlerMiddleware.ts`. Import `Request`, `Response`, `NextFunction` from `express`. Import `logger` from `../config/logger`. Export class `ApiError extends Error` with constructor `(public readonly errorCode: string, message: string, public readonly httpStatus: number)` that calls `super(message)` and sets `this.name = 'ApiError'`. Export function `errorHandlerMiddleware(err: Error, req: Request, res: Response, next: NextFunction): void`. Inside: `const requestId = (res.locals.requestId as string) || 'unknown'`. If `err instanceof ApiError`: call `logger.warn('API error', { requestId, errorCode: err.errorCode, message: err.message })`, respond `res.status(err.httpStatus).json({ meta: { request_id: requestId, request_timestamp: new Date().toISOString() }, data: { error_code: err.errorCode, title: err.name === 'ApiError' ? (err as ApiError).errorCode : 'Error', message: err.message } })`. Otherwise: call `logger.error('Unhandled error', { requestId, error: err.message, stack: err.stack })`, respond `res.status(500).json({ meta: { request_id: requestId, request_timestamp: new Date().toISOString() }, data: { error_code: 'ERR_105', title: 'Internal Server Error', message: 'An unexpected error occurred' } })`.

**Task 3.4.b — Create MtiScoresService** — file: `source/src/services/MtiScoresService.ts`

Create `source/src/services/MtiScoresService.ts`. Import `MtiScoresRepository` from `../repositories/MtiScoresRepository`. Import `MtiScoreDataDto` from `../dto/MtiScoreResponseDto`. Import `ApiError` from `../middleware/errorHandlerMiddleware`. Import `logger` from `../config/logger`. Export class `MtiScoresService` with constructor `constructor(private readonly repository: MtiScoresRepository)`. Add `private static readonly log = logger.child({ class: 'MtiScoresService' })`.

Implement `async getMtiScores(imoNumber: string, year?: number, month?: number): Promise<MtiScoreDataDto>`. Log at INFO on entry: `MtiScoresService.log.info('Getting MTI scores', { imoNumber, year, month })`. Wrap body in try/catch. In try: if `year !== undefined && month !== undefined` call `this.repository.findByYearAndMonth(imoNumber, year, month)`, else if `year !== undefined` call `this.repository.findByYear(imoNumber, year)`, else call `this.repository.findLatest(imoNumber)`. Assign to `const record`. If `record === null`: log WARN `MtiScoresService.log.warn('No MTI scores found', { imoNumber, year, month })`, throw `new ApiError('ERR_101', \`No MTI scores found for IMO ${imoNumber}\`, 404)`. Return `{ imo_number: record.imo_number, year: record.year, month: record.month, scores: { mti_score: record.mti_score, vessel_score: record.vessel_score, reporting_score: record.reporting_score, voyages_score: record.voyages_score, emissions_score: record.emissions_score, sanctions_score: record.sanctions_score }, metadata: { created_at: record.created_at.toISOString(), updated_at: record.updated_at.toISOString() } }`. In catch: if `err instanceof ApiError` rethrow; else log ERROR `MtiScoresService.log.error('Database error in getMtiScores', { imoNumber, error: (err as Error).message })` and throw `new ApiError('ERR_105', 'Internal Server Error', 500)`.

**Complexity:** M | **Dependencies:** Stories 2.2, 2.3, 3.2

---

#### Story 3.5: Controller

**As a** developer **I want** a controller that handles the HTTP layer **so that** it parses parameters, invokes validation, delegates to the service, and constructs the response envelope.

**Background for implementer:** Express route parameters (`req.params.imo`) are always strings. Query parameters (`req.query.year`, `req.query.month`) are strings when present or `undefined` when absent. Both `year` and `month` must be parsed with `parseInt(..., 10)` and then checked for `NaN` before being passed to the validator. `NaN` arises when the query param is a non-numeric string (e.g., `?year=abc`). `NaN` for year or month should produce ERR_104 since it represents an invalid date value. The `request_timestamp` is captured at the top of the handler (not inside the try block) to reflect the true request receipt time.

**Acceptance Criteria:**
- [ ] `mtiScoresRouter` (Express Router) exported from `source/src/controllers/MtiScoresController.ts`
- [ ] `GET /vessels/:imo/mti-scores` handler is `async` and calls `next(err)` in catch
- [ ] `year` and `month` parsed with `parseInt(..., 10)`; NaN triggers ERR_104 via `next(new ApiError(...))`
- [ ] Validation called before service; failures go to `next(new ApiError(...))`
- [ ] 200 response: `{ meta: { request_id, request_timestamp }, data: <MtiScoreDataDto> }`
- [ ] INFO log: `'MTI scores request received'` with `{ requestId, imo, year, month }`

**Tasks:**

**Task 3.5.a — Create MtiScoresController** — file: `source/src/controllers/MtiScoresController.ts`

Create `source/src/controllers/MtiScoresController.ts`. Import `Router`, `Request`, `Response`, `NextFunction` from `express`. Import `MtiScoresService` from `../services/MtiScoresService`. Import `MtiScoresRepository` from `../repositories/MtiScoresRepository`. Import `pool` from `../config/database`. Import `validateMtiScoresRequest` from `../validators/mtiScoresValidator`. Import `ApiError` from `../middleware/errorHandlerMiddleware`. Import `ErrorCodes` from `../dto/ErrorResponseDto`. Import `logger` from `../config/logger`.

Instantiate: `const repository = new MtiScoresRepository(pool)`, `const service = new MtiScoresService(repository)`. Define `const log = logger.child({ controller: 'MtiScoresController' })`. Export `const mtiScoresRouter = Router()`.

Register: `mtiScoresRouter.get('/vessels/:imo/mti-scores', async (req: Request, res: Response, next: NextFunction): Promise<void> => { const requestTimestamp = new Date().toISOString(); const requestId = req.requestId; const imo = req.params.imo; const rawYear = req.query.year as string | undefined; const rawMonth = req.query.month as string | undefined; const year = rawYear !== undefined ? parseInt(rawYear, 10) : undefined; const month = rawMonth !== undefined ? parseInt(rawMonth, 10) : undefined; log.info('MTI scores request received', { requestId, imo, year, month }); if (rawYear !== undefined && isNaN(year as number)) { return next(new ApiError('ERR_104', 'Year must be a valid integer', 400)) } if (rawMonth !== undefined && isNaN(month as number)) { return next(new ApiError('ERR_104', 'Month must be a valid integer', 400)) } const validation = validateMtiScoresRequest({ imo, year, month }); if (!validation.valid) { const ec = ErrorCodes[validation.errorCode as keyof typeof ErrorCodes]; return next(new ApiError(validation.errorCode, validation.message, ec?.httpStatus || 400)) } try { const data = await service.getMtiScores(imo, year, month); res.status(200).json({ meta: { request_id: requestId, request_timestamp: requestTimestamp }, data }) } catch (err) { next(err) } })`.

**Complexity:** M | **Dependencies:** Stories 2.2, 3.1, 3.2, 3.4

---

#### Story 3.6: Docker Configuration

**As a** developer **I want** a Dockerfile and docker-compose file **so that** the API and its PostgreSQL dependency can be run locally in containers.

**Acceptance Criteria:**
- [ ] `source/Dockerfile` uses a two-stage build: `builder` compiles TypeScript, final stage copies `dist/`
- [ ] `source/docker-compose.yml` starts `postgres:15-alpine` and the API service on port 3000
- [ ] `docker-compose up` results in the API reachable at `http://localhost:3000/api/v1/vessels/.../mti-scores`

**Tasks:**

**Task 3.6.a — Create Dockerfile** — file: `source/Dockerfile`

Create `source/Dockerfile` with two stages. Stage 1: `FROM node:20-alpine AS builder`, `WORKDIR /app`, `COPY package*.json ./`, `RUN npm ci`, `COPY src/ ./src/`, `COPY tsconfig.json ./`, `RUN npm run build`. Stage 2: `FROM node:20-alpine`, `WORKDIR /app`, `COPY package*.json ./`, `RUN npm ci --omit=dev`, `COPY --from=builder /app/dist ./dist`, `ENV NODE_ENV=production`, `EXPOSE 3000`, `CMD ["node", "dist/server.js"]`.

**Task 3.6.b — Create docker-compose.yml** — file: `source/docker-compose.yml`

Create `source/docker-compose.yml` with version `"3.9"`. Define service `postgres` using image `postgres:15-alpine` with environment `POSTGRES_DB: mti_scores`, `POSTGRES_USER: mti_user`, `POSTGRES_PASSWORD: mti_password`, volumes mounting `./db/migrations/V1__create_mti_scores_history.sql` to `/docker-entrypoint-initdb.d/01_schema.sql` and `./db/migrations/V2__seed_test_data.sql` to `/docker-entrypoint-initdb.d/02_seed.sql`. Define service `api` with `build: .`, `ports: ["3000:3000"]`, environment `DATABASE_URL: postgresql://mti_user:mti_password@postgres:5432/mti_scores`, `PORT: 3000`, `NODE_ENV: development`, `depends_on: [postgres]`.

**Complexity:** S | **Dependencies:** Story 1.5

---

### Epic 4: Testing

**Goal:** Achieve ≥80% line coverage through unit and integration tests that verify all acceptance criteria from the PRD.
**Priority:** High | **Estimated Complexity:** L

---

#### Story 4.1: Validator Unit Tests

**As a** developer **I want** unit tests for the validator **so that** all validation rules and their exact error codes are verifiably correct.

**Acceptance Criteria:**
- [ ] All PRD acceptance criteria errors (ERR_102, ERR_103, ERR_104) are tested
- [ ] Valid combinations (no params, year only, year+month) all return `{ valid: true }`
- [ ] 11 test cases cover boundary values

**Tasks:**

**Task 4.1.a — Create validator unit tests** — file: `source/test/unit/mtiScoresValidator.test.ts`

Create `source/test/unit/mtiScoresValidator.test.ts`. Import `validateMtiScoresRequest` and `ValidationParams` from `../../src/validators/mtiScoresValidator`. Write `describe('validateMtiScoresRequest', ...)` with these 11 test cases (each using `it('...')`):
(1) `'returns valid:true for 7-digit IMO, no year/month'` — params `{ imo: '9123456', year: undefined, month: undefined }`, expect `result.valid === true`;
(2) `'returns ERR_103 for IMO shorter than 7 digits'` — params `{ imo: '123', year: undefined, month: undefined }`, expect `result.valid === false`, `(result as any).errorCode === 'ERR_103'`;
(3) `'returns ERR_103 for IMO longer than 7 digits'` — params `{ imo: '12345678', ... }`, same ERR_103 assertions;
(4) `'returns ERR_103 for IMO with non-digit characters'` — params `{ imo: 'abc1234', ... }`, ERR_103;
(5) `'returns ERR_102 for month without year'` — params `{ imo: '9123456', year: undefined, month: 6 }`, expect errorCode `'ERR_102'`;
(6) `'returns ERR_104 for year below 2000'` — params `{ imo: '9123456', year: 1999, month: undefined }`, expect errorCode `'ERR_104'`;
(7) `'returns ERR_104 for year above 2100'` — params `{ imo: '9123456', year: 2101, month: undefined }`, errorCode `'ERR_104'`;
(8) `'returns ERR_104 for month value 13'` — params `{ imo: '9123456', year: 2023, month: 13 }`, errorCode `'ERR_104'`;
(9) `'returns ERR_104 for month value 0'` — params `{ imo: '9123456', year: 2023, month: 0 }`, errorCode `'ERR_104'`;
(10) `'returns valid:true for valid year without month'` — params `{ imo: '9123456', year: 2023, month: undefined }`, `result.valid === true`;
(11) `'returns valid:true for valid year and month'` — params `{ imo: '9123456', year: 2023, month: 6 }`, `result.valid === true`.

**Complexity:** S | **Dependencies:** Story 3.2

---

#### Story 4.2: Service Unit Tests

**As a** developer **I want** unit tests for the service that mock the repository **so that** business logic is verified in isolation from the database.

**Acceptance Criteria:**
- [ ] Repository is auto-mocked with `jest.mock()`
- [ ] All three repository method selection branches are tested
- [ ] ERR_101 thrown when repository returns null
- [ ] ERR_105 thrown when repository throws
- [ ] Null score fields pass through as null in the return value

**Tasks:**

**Task 4.2.a — Create service unit tests** — file: `source/test/unit/MtiScoresService.test.ts`

Create `source/test/unit/MtiScoresService.test.ts`. Import `MtiScoresService` from `../../src/services/MtiScoresService`. Import `MtiScoresRepository` from `../../src/repositories/MtiScoresRepository`. Import `ApiError` from `../../src/middleware/errorHandlerMiddleware`. Import `MtiScoreRecord` from `../../src/models/MtiScoreRecord`. Call `jest.mock('../../src/repositories/MtiScoresRepository')`.

Define `const mockRecord: MtiScoreRecord = { id: 1, imo_number: '9123456', year: 2024, month: 1, mti_score: 85.50, vessel_score: 90.00, reporting_score: 88.75, voyages_score: 82.30, emissions_score: 87.60, sanctions_score: 100.00, created_at: new Date('2024-01-01T00:00:00Z'), updated_at: new Date('2024-01-01T00:00:00Z') }`.

In `beforeEach`: create `mockRepository = new MtiScoresRepository(null as any)`, create `service = new MtiScoresService(mockRepository)`.

Write 6 test cases:
(1) `'calls findLatest when no year or month'` — mock `jest.spyOn(mockRepository, 'findLatest').mockResolvedValue(mockRecord)`, call `service.getMtiScores('9123456')`, assert `mockRepository.findLatest` called with `'9123456'`, assert result `imo_number === '9123456'` and `scores.mti_score === 85.50`;
(2) `'calls findByYear when year provided without month'` — mock `findByYear` to resolve `mockRecord`, call `service.getMtiScores('9123456', 2023)`, assert `findByYear` called with `('9123456', 2023)`;
(3) `'calls findByYearAndMonth when both year and month provided'` — mock `findByYearAndMonth` to resolve `mockRecord`, call `service.getMtiScores('9123456', 2023, 6)`, assert called with `('9123456', 2023, 6)`;
(4) `'throws ApiError ERR_101 when record not found'` — mock `findLatest` to resolve `null`, expect `service.getMtiScores('9999999')` to reject, in catch assert `err instanceof ApiError` and `err.errorCode === 'ERR_101'`;
(5) `'throws ApiError ERR_105 on database error'` — mock `findLatest` to reject with `new Error('Connection refused')`, expect `getMtiScores` to reject with `ApiError` where `errorCode === 'ERR_105'`;
(6) `'returns null score fields as null in response'` — mock `findLatest` to resolve `{ ...mockRecord, mti_score: null }`, call `getMtiScores`, assert `result.scores.mti_score === null`.

**Complexity:** M | **Dependencies:** Stories 3.4, 2.3

---

#### Story 4.3: Integration Tests

**As a** developer **I want** end-to-end integration tests against a real PostgreSQL container **so that** all 8 PRD acceptance criteria are verified in the full request/response cycle.

**Background for implementer:** Testcontainers starts a real `postgres:15` Docker container for the test suite. The container's `connectionString` must be set as `process.env.DATABASE_URL` *before* the `app` module is imported, because `env.ts` reads `DATABASE_URL` at module load time. Use dynamic `import()` inside `beforeAll` after setting the env var to guarantee import order. The migration SQL is applied directly via `pool.query()` using `fs.readFileSync`. Seed data is inserted via parameterized INSERT statements (not the seed SQL file) so the integration test is self-contained.

**Acceptance Criteria:**
- [ ] AC1 (latest scores): 200, `data.imo_number === '9123456'`, `data.year === 2024`, `data.month === 1`, `data.scores.mti_score === 85.5`, `meta.request_id` matches UUID regex `/^[0-9a-f-]{36}$/`
- [ ] AC2 (year filter): 200, `data.year === 2023`, `data.month === 12`
- [ ] AC3 (year+month filter): 200, `data.month === 6`, `data.scores.mti_score === 75.5`
- [ ] AC4 (IMO not found): 404, `data.error_code === 'ERR_101'`
- [ ] AC5 (invalid IMO format): 400, `data.error_code === 'ERR_103'`
- [ ] AC6 (month without year): 400, `data.error_code === 'ERR_102'`
- [ ] AC7 (invalid month 13): 400, `data.error_code === 'ERR_104'`
- [ ] AC8 (null scores): 200, `data.scores.mti_score === null`, `data.scores.vessel_score === null`

**Tasks:**

**Task 4.3.a — Create integration tests** — file: `source/test/integration/mtiScores.integration.test.ts`

Create `source/test/integration/mtiScores.integration.test.ts`. Import `PostgreSqlContainer` and `StartedPostgreSqlContainer` from `testcontainers`. Import `supertest` from `supertest`. Import `fs` from `fs`. Import `path` from `path`. Import `Pool` from `pg`. Declare `let container: StartedPostgreSqlContainer`, `let pool: Pool`, `let app: any`.

In `beforeAll`: start `container = await new PostgreSqlContainer('postgres:15').start()`. Set `process.env.DATABASE_URL = container.getConnectionUri()`. Dynamically import: `app = (await import('../../src/app')).default`. Create `pool = new Pool({ connectionString: container.getConnectionUri() })`. Apply migration: `await pool.query(fs.readFileSync(path.join(__dirname, '../../db/migrations/V1__create_mti_scores_history.sql'), 'utf-8'))`. Insert seed rows using `pool.query(INSERT INTO mti_scores_history ...)` for all four rows matching the V2 seed data exactly: row 1 `('9123456', 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00)`, row 2 `('9123456', 2023, 12, 80.00, 85.00, 83.50, 78.00, 82.00, 95.00)`, row 3 `('9123456', 2023, 6, 75.50, 80.00, 78.25, 72.30, 77.60, 90.00)`, row 4 `('9999998', 2024, 3, NULL, NULL, NULL, NULL, NULL, NULL)`.

In `afterAll`: `await pool.end()`, `await container.stop()`.

Write 8 test cases using `supertest(app)`:
(1) AC1: `GET /api/v1/vessels/9123456/mti-scores` → status 200, `body.data.imo_number === '9123456'`, `body.data.year === 2024`, `body.data.month === 1`, `body.data.scores.mti_score === 85.5`, `body.meta.request_id` matches `/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i`;
(2) AC2: `GET /api/v1/vessels/9123456/mti-scores?year=2023` → status 200, `body.data.year === 2023`, `body.data.month === 12`;
(3) AC3: `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6` → status 200, `body.data.month === 6`, `body.data.scores.mti_score === 75.5`;
(4) AC4: `GET /api/v1/vessels/9999999/mti-scores` → status 404, `body.data.error_code === 'ERR_101'`;
(5) AC5: `GET /api/v1/vessels/123/mti-scores` → status 400, `body.data.error_code === 'ERR_103'`;
(6) AC6: `GET /api/v1/vessels/9123456/mti-scores?month=6` → status 400, `body.data.error_code === 'ERR_102'`;
(7) AC7: `GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13` → status 400, `body.data.error_code === 'ERR_104'`;
(8) AC8: `GET /api/v1/vessels/9999998/mti-scores` → status 200, `body.data.scores.mti_score === null`, `body.data.scores.vessel_score === null`, `body.data.scores.reporting_score === null`.

**Complexity:** L | **Dependencies:** Stories 3.5, 4.1, 4.2

---

### Backend API Contracts

```
GET /api/v1/vessels/{imo}/mti-scores

Path Parameters:
  imo     string    Required    7-digit vessel IMO number matching ^[0-9]{7}$

Query Parameters:
  year    integer   Optional    Filter by year; range 2000–2100
  month   integer   Optional    Filter by month (1–12); requires year to also be present

Request Headers:
  X-API-Key: API key value used as rate limiting identity key (falls back to req.ip)

Success Response — 200:
  meta.request_id               string (UUID v4)    Correlation ID for request tracing
  meta.request_timestamp        string (ISO 8601)   Timestamp when request was received
  data.imo_number               string              7-digit IMO number
  data.year                     integer             Year of the scores record
  data.month                    integer             Month of the scores record (1–12)
  data.scores.mti_score         number | null       Overall MTI composite score
  data.scores.vessel_score      number | null       Vessel component score
  data.scores.reporting_score   number | null       Reporting component score
  data.scores.voyages_score     number | null       Voyages component score
  data.scores.emissions_score   number | null       Emissions component score
  data.scores.sanctions_score   number | null       Sanctions component score
  data.metadata.created_at      string (ISO 8601)   Record creation timestamp
  data.metadata.updated_at      string (ISO 8601)   Record last updated timestamp

Error Response — 4XX / 5XX:
  meta.request_id               string (UUID v4)
  meta.request_timestamp        string (ISO 8601)
  data.error_code               string
  data.title                    string
  data.message                  string

Error Code Reference:
  ERR_101   404   No scores found for given IMO / year / month combination
  ERR_102   400   Month parameter specified without year parameter
  ERR_103   400   IMO number is not exactly 7 digits or contains non-digit characters
  ERR_104   400   Year outside range 2000–2100, or month outside range 1–12, or non-integer
  ERR_105   500   Database connection failure or query execution error
  ERR_429   429   Rate limit exceeded (100 requests per minute per X-API-Key)
```

### Backend Non-Functional Requirements

| Concern | Requirement |
|---|---|
| Performance | p95 < 200ms; composite index `(imo_number, year DESC, month DESC)` required; pool max 10 connections |
| Logging | Winston JSON in production; colorized simple in development; child loggers carry `requestId`; DEBUG level logs query execution; INFO logs request entry and service entry; WARN logs not-found; ERROR logs unhandled exceptions |
| Metrics | Log request count and duration at INFO level per request (extensible to Prometheus) |
| Security | Parameterized SQL via pg `$1`/`$2`/`$3` syntax; no raw string interpolation in queries; no stack traces in error responses; input validated before any DB call |
| Rate Limiting | express-rate-limit; in-memory token window; 100 requests / 60s per X-API-Key header; falls back to req.ip; 429 response uses standard JSON envelope |
| Testing | ≥80% line coverage; unit tests for validator and service (mocked); integration tests via testcontainers against real PostgreSQL |
| Health / Docs | OpenAPI specification provided in PRD; no dedicated `/health` endpoint specified |

---

### Cross-Cutting Dependency Map

| Class | Depends On | Reason |
|---|---|---|
| `MtiScoresController` | `MtiScoresRepository`, `pool`, `MtiScoresService`, `validateMtiScoresRequest`, `ApiError`, `ErrorCodes`, `logger` | Orchestrates full request lifecycle |
| `MtiScoresService` | `MtiScoresRepository`, `ApiError`, `logger` | Business logic and query selection |
| `MtiScoresRepository` | `pool` (pg Pool), `MtiScoreRecord`, `logger` | SQL execution and pg row mapping |
| `requestIdMiddleware` | `uuid` (v4), `logger` | Generates and attaches correlation ID |
| `rateLimiterMiddleware` | `config` (`rateLimitWindowMs`, `rateLimitMaxRequests`), `res.locals.requestId` | Rate limiting with custom error envelope |
| `errorHandlerMiddleware` | `ApiError`, `logger`, `res.locals.requestId` | Centralized error-to-HTTP-response mapping |
| `app.ts` | All middleware modules, `mtiScoresRouter` | Express application wiring |
| `database.ts` | `config.databaseUrl`, `logger` | PostgreSQL pool singleton |
| `logger.ts` | `config.nodeEnv` | Log level and format selection |
| `env.ts` | `process.env` | Environment variable validation and export |

---

### Backend Implementation Order (Recommended Sequence)

1. **Story 1.1** — Project scaffolding; nothing compiles without package.json and tsconfig
2. **Story 1.2** — Environment config; required by logger and database pool at module load time
3. **Story 1.3** — Logger; required by database pool and all business classes
4. **Story 1.4** — Database pool; required by repository
5. **Story 2.1** — Database migration SQL; can be written in parallel with Stories 1.2–1.4
6. **Story 2.2** — Domain models and DTOs; no code dependencies, parallelizable
7. **Story 2.3** — Repository; depends on pool (1.4) and models (2.2)
8. **Story 3.1** — Request ID middleware; depends on logger (1.3)
9. **Story 3.2** — Input validator; depends on DTOs (2.2)
10. **Story 3.3** — Rate limiter middleware; depends on env config (1.2)
11. **Story 3.4** — ApiError + service layer; depends on repository (2.3) and DTOs (2.2)
12. **Story 3.5** — Controller; depends on all preceding stories
13. **Story 1.5** — App bootstrap; depends on all middleware and router existing
14. **Story 3.6** — Docker configuration; depends on app working end-to-end
15. **Story 4.1** — Validator unit tests; can run in parallel with Stories 3.3–3.5
16. **Story 4.2** — Service unit tests; after Story 3.4
17. **Story 4.3** — Integration tests; after Story 3.5

> Stories 2.1 and 2.2 can be developed in parallel from the start. Stories 4.1 and 4.2 can be developed in parallel once their subjects exist.

---

## FRONTEND IMPLEMENTATION PLAN

This PRD is **backend-only**. No frontend implementation required.

---

## INTEGRATION & SHARED CONTRACTS

### Shared Types / DTOs

| Type/Record | Fields | JSON field names | Notes |
|---|---|---|---|
| `MetaDto` | `request_id: string`, `request_timestamp: string` | `request_id`, `request_timestamp` | Present in every response (success and error) |
| `MtiScoreDataDto` | `imo_number: string`, `year: number`, `month: number`, `scores: ScoresDto`, `metadata: { created_at: string; updated_at: string }` | snake_case throughout | Core data payload for success responses |
| `ScoresDto` | `mti_score: number\|null`, `vessel_score: number\|null`, `reporting_score: number\|null`, `voyages_score: number\|null`, `emissions_score: number\|null`, `sanctions_score: number\|null` | snake_case | All six fields nullable |
| `ErrorDataDto` | `error_code: string`, `title: string`, `message: string` | `error_code`, `title`, `message` | Used in all error responses |
| `MtiScoreRecord` | `id: number`, `imo_number: string`, `year: number`, `month: number`, six nullable score fields, `created_at: Date`, `updated_at: Date` | N/A (internal only) | Internal DB row model; not serialized directly |

### Environment Variables Required

| Variable | Required? | Example Value | Description |
|---|---|---|---|
| `DATABASE_URL` | Yes | `postgresql://mti_user:mti_password@localhost:5432/mti_scores` | PostgreSQL connection string |
| `PORT` | No | `3000` | HTTP server listen port; defaults to 3000 |
| `NODE_ENV` | No | `production` | Controls log format (`production` → JSON) and level |
| `RATE_LIMIT_WINDOW_MS` | No | `60000` | Rate limit rolling window in milliseconds |
| `RATE_LIMIT_MAX_REQUESTS` | No | `100` | Maximum requests per window per API key |

### Database Schema

Table: `mti_scores_history` — created by migration `V1__create_mti_scores_history.sql`

| Column | Type | Nullable | Constraint |
|---|---|---|---|
| `id` | BIGSERIAL | No | Primary key |
| `imo_number` | VARCHAR(7) | No | CHECK `imo_number ~ '^[0-9]{7}$'` |
| `year` | INTEGER | No | CHECK `year BETWEEN 2000 AND 2100` |
| `month` | INTEGER | No | CHECK `month BETWEEN 1 AND 12` |
| `mti_score` | NUMERIC(5,2) | Yes | — |
| `vessel_score` | NUMERIC(5,2) | Yes | — |
| `reporting_score` | NUMERIC(5,2) | Yes | — |
| `voyages_score` | NUMERIC(5,2) | Yes | — |
| `emissions_score` | NUMERIC(5,2) | Yes | — |
| `sanctions_score` | NUMERIC(5,2) | Yes | — |
| `created_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |
| `updated_at` | TIMESTAMP WITH TIME ZONE | No | DEFAULT NOW() |

Additional constraints:
- Composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` — supports all three SELECT query variants from the PRD
- Table-level CHECK constraints: `chk_imo_format`, `chk_month_range`, `chk_year_range`

---

## RISK ASSESSMENT

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `pg` returns NUMERIC columns as strings causing JSON type mismatch | High | High | Explicit `parseFloat()` in `MtiScoresRepository.mapRow()` for all six score fields |
| Testcontainers slow startup exceeds Jest default timeout | Medium | Low | Set `testTimeout: 60000` in `jest.config.ts`; use `beforeAll` (not `beforeEach`) for container lifecycle |
| `parseInt()` returns `NaN` for non-numeric query param string | Medium | High | Explicit `isNaN()` check in controller before validation, producing ERR_104 |
| PostgreSQL connection pool exhaustion under load | Low | High | `max: 10`, `connectionTimeoutMillis: 2000` fails fast; ERR_105 returned to client |
| Module import order causes `env.ts` to throw in tests before `DATABASE_URL` is set | Medium | High | Use dynamic `import()` of `app` inside `beforeAll` after setting `process.env.DATABASE_URL` |

---

## DEFINITION OF DONE

### For Each Story
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing (target: ≥80% line coverage)
- [ ] Integration tests passing
- [ ] No new TypeScript compilation errors
- [ ] No new linting errors
- [ ] All acceptance criteria verified

### For the Release
- [ ] All stories complete
- [ ] Performance targets verified (p95 < 200ms under representative load)
- [ ] Security review passed (parameterized queries, no sensitive data in errors)
- [ ] API documentation updated (OpenAPI spec in PRD confirmed accurate)
- [ ] Docker image builds successfully and API responds at `http://localhost:3000/api/v1/vessels/9123456/mti-scores`
- [ ] All environment variables documented in README

---

## IMPLEMENTATION ORDER (Recommended Sequence)

1. **Story 1.1** — Project scaffolding; required for everything else to compile
2. **Story 1.2** — Environment configuration; needed by logger and database pool at load time
3. **Story 1.3** — Logger; needed by database pool and all business classes
4. **Story 1.4** — Database pool; needed by repository
5. **Story 2.1** — Database migration SQL; parallelizable with 1.2–1.4
6. **Story 2.2** — Domain models and DTOs; no dependencies, parallelizable
7. **Story 2.3** — Repository; depends on pool (1.4) and models (2.2)
8. **Story 3.1** — Request ID middleware; depends on logger (1.3)
9. **Story 3.2** — Input validator; depends on DTOs (2.2)
10. **Story 3.3** — Rate limiter middleware; depends on env config (1.2)
11. **Story 3.4** — ApiError class and service layer; depends on repository (2.3) and DTOs (2.2)
12. **Story 3.5** — Controller; depends on all preceding API layer stories
13. **Story 1.5** — App bootstrap; depends on all middleware and router modules existing
14. **Story 3.6** — Docker configuration; depends on app working end-to-end
15. **Story 4.1** — Validator unit tests; parallelizable with Stories 3.3–3.5
16. **Story 4.2** — Service unit tests; after Story 3.4
17. **Story 4.3** — Integration tests; after Story 3.5

> **Parallel tracks:** Stories 2.1 and 2.2 can be written immediately in parallel with infrastructure setup. Stories 4.1 and 4.2 can be developed in parallel once their subject code exists.
