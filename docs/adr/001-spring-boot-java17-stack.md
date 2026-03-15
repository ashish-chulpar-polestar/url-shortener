# ADR-001: Spring Boot 3.2 with Java 17 as the Application Stack

**Status:** Accepted
**Date:** 2026-03-15
**Feature:** MTI Scores API - Test1

## Context

The MTI Scores API is a new greenfield service with no prior technology commitments. We needed to choose a language, runtime, and framework to build a single REST endpoint backed by PostgreSQL. Options considered included Node.js/Express, Python/FastAPI, and Java/Spring Boot. The team has existing expertise in Java-based enterprise services, and the API requires robust input validation, structured error handling, database migrations, and production-grade observability out of the box.

## Decision

Use **Java 17** (LTS) as the language and **Spring Boot 3.2.3** as the application framework, built with **Maven 3.9** using `spring-boot-starter-parent:3.2.3` as the BOM. Spring Boot provides auto-configured embedded Tomcat, `spring-boot-starter-validation` (Jakarta Bean Validation / Hibernate Validator) for declarative `@Pattern`, `@Min`, and `@Max` constraints on controller parameters, `spring-boot-starter-actuator` for `/actuator/health`, and `springdoc-openapi-starter-webmvc-ui:2.3.0` for automatic Swagger UI generation. Java 17 LTS records and sealed classes simplify immutable DTOs.

## Consequences

- Spring Boot auto-configuration reduces boilerplate; all components in the `com.example.mti` base package are detected automatically.
- Jakarta Bean Validation requires `@Validated` at the controller class level (not just `@Valid`) for `@PathVariable` and `@RequestParam` constraints to fire — a non-obvious requirement documented in Story 8.1.
- Flyway schema migrations are auto-run at startup; the application will fail fast if the database is unreachable, which is the desired behavior.
- The Spring Boot BOM manages Flyway, Jackson, and SLF4J versions, reducing dependency conflict risk.
- Choosing Java over Node.js/Python means a longer cold-start time for containerized deployments, but this is acceptable for a persistent API service.
