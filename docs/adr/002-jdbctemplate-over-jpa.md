# ADR-002: Spring JdbcTemplate over JPA/Hibernate for Data Access

**Status:** Accepted
**Date:** 2026-03-15
**Feature:** MTI Scores API - Test1

## Context

The MTI Scores API reads from a single table (`mti_scores_history`) using three well-defined query patterns specified in the PRD: latest record by IMO, latest record by IMO and year, and exact record by IMO, year, and month. All queries are SELECT-only, parameterized, and return at most one row. We considered Spring Data JPA (with Hibernate) and Spring JdbcTemplate. JPA is the typical Spring Boot default for database access but introduces ORM abstractions that add complexity for a read-only, single-table use case.

## Decision

Use **Spring JdbcTemplate** (via `spring-boot-starter-jdbc`) for all database access in `MtiScoresRepository`. The three query variants are implemented as explicit SQL strings passed to `jdbcTemplate.queryForObject`, with a static `RowMapper<MtiScore>` constant that maps all 12 columns from `mti_scores_history`. `EmptyResultDataAccessException` is caught and converted to `Optional.empty()`.

## Consequences

- SQL queries are explicit, visible, and match exactly the query logic documented in the PRD — no surprises from Hibernate-generated SQL or lazy-loading.
- Parameterized queries via JdbcTemplate prevent SQL injection without requiring an ORM.
- `rs.getObject("created_at", OffsetDateTime.class)` correctly preserves timezone offsets from `TIMESTAMP WITH TIME ZONE` columns; `rs.getTimestamp` would lose timezone information — this is documented in Story 4.1.
- No JPA entity annotations are needed on `MtiScore`; it is a plain Java object, simplifying the model.
- Adding write operations in the future will require explicit INSERT/UPDATE SQL; JPA's `save()` convenience would not be available without a migration to JPA.
- There is no first-level cache; each request executes a fresh query, which is appropriate for a read-heavy API where data freshness is important.
