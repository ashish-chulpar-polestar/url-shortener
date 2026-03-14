# ADR-003: JdbcTemplate over JPA for Data Access

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The MTI Scores API performs read-only access on a single table (`mti_scores_history`) using three SQL query patterns that are explicitly specified in the PRD. A data access approach needed to be chosen for executing these queries and mapping results to the `MtiScoreRecord` domain object.

Options considered:

1. Spring Data JPA with Hibernate — provides derived query methods, entity mapping, and a full ORM layer.
2. Spring JdbcTemplate — executes raw parameterized SQL and maps rows via a `RowMapper` lambda.
3. jOOQ — type-safe SQL DSL with code generation from the schema.

## Decision

Use Spring `JdbcTemplate` with a statically declared `RowMapper<MtiScoreRecord>` named `MTI_SCORE_ROW_MAPPER`. Each of the three query methods (`findLatest`, `findByYear`, `findByYearAndMonth`) calls `jdbcTemplate.queryForObject(sql, MTI_SCORE_ROW_MAPPER, params)` and wraps the result in `Optional`, catching `EmptyResultDataAccessException` to return `Optional.empty()`. Nullable score columns are mapped using `rs.getObject(columnName, Double.class)` to correctly propagate SQL NULL as Java `null`.

## Consequences

- The SQL queries match the PRD verbatim, making the implementation directly auditable against the specification without any ORM query translation layer.
- No entity lifecycle management, dirty checking, or first-level cache overhead — appropriate for a read-only API with no write operations.
- Nullable `Double` mapping via `rs.getObject(column, Double.class)` is more explicit than JPA's `@Column(nullable = true)` with type converters, avoiding the common `0.0` vs `null` confusion with `rs.getDouble()`.
- Adding write operations in the future would require either continuing with JdbcTemplate (verbosely) or introducing JPA at that time; for the current read-only scope, JdbcTemplate is the right fit.
- jOOQ was not chosen because it requires code generation at build time, adding tooling complexity that is not justified for three static queries.
