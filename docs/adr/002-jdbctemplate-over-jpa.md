# ADR-002: JdbcTemplate Over JPA/ORM for Data Access

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD specifies three exact SQL queries with specific `ORDER BY` and `LIMIT` clauses for querying the `mti_scores_history` table. The API is strictly read-only — no inserts, updates, or deletes. The table has nullable score columns (`DECIMAL(5,2)`) that must be serialized as JSON `null` when the database value is SQL NULL.

Two data access approaches were considered within the Spring Boot ecosystem:

**Spring Data JPA (Hibernate ORM):** Auto-generates queries from repository method names, handles entity lifecycle, and provides `@Entity` mapping. However, for this API: (a) the PRD provides explicit SQL that must be followed exactly, including `ORDER BY year DESC, month DESC LIMIT 1`; (b) JPA's `@Query` annotations would just embed the same SQL strings anyway; (c) Hibernate adds a significant classpath and startup overhead for a read-only single-table API; (d) JPA's `findTop1By...OrderBy...` derived query method would not cleanly express the three-variant routing logic.

**Spring JdbcTemplate:** Executes parameterized SQL directly. The implementer controls the exact SQL string, parameter binding is handled by `?` placeholders (preventing SQL injection), and `ResultSet` mapping is explicit via a `RowMapper`. `jdbcTemplate.query(sql, rowMapper, params)` returns an empty `List` for zero rows — not an exception — making `Optional` wrapping clean.

## Decision

Use **Spring JdbcTemplate** with a custom `MtiScoreRowMapper` for all data access in `MtiScoresRepositoryImpl`.

The three SQL queries from the PRD are embedded verbatim as string constants in `MtiScoresRepositoryImpl`:
- `SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1`
- `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1`
- `SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1`

`MtiScoreRowMapper` uses `rs.getObject("mti_score", Double.class)` for nullable columns, which returns `null` (not `0.0`) when the SQL value is NULL. This is the correct behavior required by AC8.

`JdbcTemplate` is auto-configured by Spring Boot when `spring-boot-starter-jdbc` and a `DataSource` bean are present — no additional configuration class is needed.

## Consequences

**Easier:**
- Exact SQL from the PRD is implemented without translation; any discrepancy between PRD and code is immediately visible.
- Nullable score fields are correctly handled — `getObject(col, Double.class)` returns `null` for SQL NULL without special ORM annotations.
- `JdbcTemplate.query()` returning an empty list for zero rows allows clean `Optional` wrapping without try/catch.
- No Hibernate startup overhead, no entity scanning, no JPQL/HQL compilation.
- SQL injection is prevented by `?` parameterized binding — the `MtiScoresRepositoryImpl` must never use string concatenation for query construction.

**Harder / Constraints:**
- No automatic schema-to-Java mapping; column names are hard-coded as strings in `MtiScoreRowMapper`. A column rename in the database requires updating the RowMapper.
- No lazy loading, caching, or dirty tracking — acceptable since the API is read-only.
- Unit testing the repository requires either a real database or mocking `JdbcTemplate`, which is why repository-level unit tests are not included; instead, repository correctness is verified via Testcontainers integration tests in `VesselControllerIntegrationTest`.
