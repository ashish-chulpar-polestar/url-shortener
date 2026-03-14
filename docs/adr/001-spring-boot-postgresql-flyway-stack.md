# ADR-001: Spring Boot 3.2 + PostgreSQL 15 + Flyway Technology Stack

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The MTI Scores API is a greenfield backend service. No existing codebase or language was mandated by the PRD. The API serves read-only queries against a pre-populated relational table (`mti_scores_history`) and must return structured JSON with consistent metadata, validated inputs, and appropriate error codes. We needed to choose a language, web framework, database, and schema migration tool that would support DECIMAL precision for score fields, composite index optimization for three query patterns, ISO-8601 timestamps, and a well-understood deployment model.

Options considered:
- **Node.js + Express + pg**: Fits the PRD's `uuid.v4()` hint; however, dynamic typing increases risk of incorrect null handling for score fields and makes compile-time API contract enforcement harder.
- **Python + FastAPI + asyncpg**: Fast development, but async JDBC-equivalent tooling for PostgreSQL is less mature and Flyway integration requires an external runner.
- **Java 17 + Spring Boot 3.2 + PostgreSQL 15 + Flyway**: Strongly typed; Spring Boot auto-configures DataSource, JdbcTemplate, and Flyway on startup; mature ecosystem for enterprise data APIs; native support for `DECIMAL(5,2)` null handling via `ResultSet.getObject(col, Double.class)`.

## Decision

Use **Java 17** as the language, **Spring Boot 3.2** (with embedded Tomcat) as the web framework, **PostgreSQL 15** as the database, and **Flyway 9.x** for schema migration.

Spring Boot's auto-configuration initializes the `DataSource`, `JdbcTemplate`, and Flyway migration runner from `application.yml` with no manual wiring. Flyway runs `V1__create_mti_scores_history.sql` before the servlet container accepts requests, guaranteeing the schema is always up-to-date at startup. PostgreSQL 15 supports `DECIMAL(5,2)` precision for score fields, `TIMESTAMP WITH TIME ZONE` for audit columns, composite indexes for the three PRD query patterns, and a `CHECK` constraint on the `month` column. Java 17's record types provide immutable DTOs and domain models with minimal boilerplate.

Maven 3.9 is used as the build tool, producing a single executable JAR via `spring-boot-maven-plugin` for Docker deployment.

## Consequences

**Easier:**
- Spring Boot auto-configuration eliminates manual bean wiring for DataSource, JdbcTemplate, and Flyway.
- Flyway guarantees the `mti_scores_history` schema is applied before any request is served, even in fresh environments.
- Java's `ResultSet.getObject(column, Double.class)` correctly returns `null` for SQL NULL, satisfying AC8 (partial score data).
- PostgreSQL's composite index on `(imo_number, year DESC, month DESC)` directly maps to all three PRD query patterns, meeting the p95 < 200ms requirement.
- Spring Boot's Actuator provides `/actuator/health` with no additional code.

**Harder / Constraints:**
- Java startup time is slower than Node.js or Python; mitigated by JRE-based Docker image (`eclipse-temurin:17-jre-jammy`).
- Requires a running PostgreSQL instance for all integration tests; mitigated by Testcontainers (ADR-002 covers the JdbcTemplate choice, and test isolation is handled by `@Sql` seed data).
- The team must maintain the Flyway migration file sequence — out-of-order migrations will fail on startup by design.
