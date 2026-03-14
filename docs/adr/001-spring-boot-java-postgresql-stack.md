# ADR-001: Spring Boot 3.2 on Java 17 with PostgreSQL as the Technology Stack

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The MTI Scores API is a greenfield backend service with no existing codebase constraints. The service needs to expose a single read-only REST endpoint that queries a relational database, enforces input validation, returns a structured JSON envelope, and is deployable via Docker. A technology stack needed to be chosen for the language, framework, and database layers.

Considered options for the language and framework included Java with Spring Boot, Python with FastAPI, and Node.js with Express. For the database, options included PostgreSQL, MySQL, and a document store such as MongoDB.

## Decision

Use Java 17 (LTS) with Spring Boot 3.2 for the application framework, and PostgreSQL 15 as the relational database. Maven 3.9 is used as the build tool, and Flyway is used for schema migrations. Spring Boot's embedded Tomcat, auto-configured `JdbcTemplate`, and built-in Actuator health endpoint eliminate the need for external application server setup or manual health check wiring.

## Consequences

- Spring Boot's convention-over-configuration approach reduces boilerplate; the full web, JDBC, validation, and actuator stacks are available with four starter dependencies.
- Java 17 records are used for domain models and DTOs, reducing verbosity compared to traditional POJOs.
- PostgreSQL's `NUMERIC(6,2)` type maps cleanly to Java `Double` for score values, and its JDBC driver supports `getObject(column, OffsetDateTime.class)` for timestamp columns without additional type converters.
- Flyway integrates natively with Spring Boot's auto-configuration, running migrations automatically on startup with no extra code.
- The team must have a JDK 17 and Maven 3.9 development environment; the Dockerfile handles this for CI and deployment.
- Future scaling (connection pooling, read replicas) can be added via HikariCP configuration without changing application code.
