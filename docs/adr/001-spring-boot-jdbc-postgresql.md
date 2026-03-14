# ADR-001: Spring Boot 3.2 with Spring JDBC over PostgreSQL

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The MTI Scores API requires a server-side runtime that can expose a REST endpoint, execute parameterised SQL queries against a relational database, and produce structured JSON responses. The team needed to select a language, framework, and database that provide strong typing, mature ecosystem support, and straightforward containerisation. The three main options considered were: (1) Spring Boot (Java) with JPA/Hibernate, (2) Spring Boot (Java) with Spring JDBC / JdbcTemplate, and (3) Node.js (Express) with a PostgreSQL client.

The PRD specifies three exact SQL query patterns (latest, by-year, by-year-and-month) with explicit `ORDER BY` and `LIMIT` clauses, and a precise column mapping including nullable `NUMERIC(5,2)` fields. This makes explicit SQL preferable to an ORM abstraction where NULL handling and generated query order can be non-obvious.

## Decision

Use **Java 17**, **Spring Boot 3.2**, and **Spring JDBC (`JdbcTemplate`)** with **PostgreSQL 15** as the backing store. ORM (JPA/Hibernate) is explicitly excluded in favour of `JdbcTemplate` with a hand-written `RowMapper<MtiScoreRecord>`. The three PRD SQL queries are implemented verbatim using parameterised `jdbcTemplate.query(...)` calls. Schema management is handled by **Flyway 10** with SQL migration files.

## Consequences

- **Easier:** SQL queries exactly match the PRD specification; NULL handling via `rs.getObject("col", Double.class)` is explicit and correct. Flyway migrations give a clear audit trail of schema changes.
- **Harder:** More boilerplate than JPA for simple CRUD; no lazy loading or query generation. Adding a second entity in future will require manual SQL.
- **Constraint:** All future data access in this service must use `JdbcTemplate` with parameterised queries to prevent SQL injection. No raw string concatenation in SQL.
