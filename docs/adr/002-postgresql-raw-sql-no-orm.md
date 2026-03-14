# ADR-002: PostgreSQL with node-postgres (Raw SQL, No ORM)

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD specifies exact SQL queries for all three data access patterns (latest, by year, by year+month), including the `ORDER BY year DESC, month DESC LIMIT 1` sort and parameterization requirements. The data model is a single table (`mti_scores_history`) with no relationships. Alternatives considered were: using an ORM such as Prisma or TypeORM (would abstract away the exact SQL the PRD specifies, adding unnecessary indirection and a code-generation step); using a query builder such as Knex (adds a dependency without benefit for three fixed queries); using a different database such as MySQL (no benefit over PostgreSQL for this use case; PostgreSQL's `NUMERIC(5,2)` type and regex CHECK constraints are well-suited).

## Decision

Use **PostgreSQL 15** as the database and **pg 8.x (node-postgres)** as the database client. All queries are written as raw parameterized SQL strings using pg's `$1`/`$2`/`$3` positional parameter syntax, matching the PRD query examples exactly. A module-level `Pool` singleton is exported from `source/src/config/database.ts` with `max: 10`, `idleTimeoutMillis: 30000`, and `connectionTimeoutMillis: 2000`. A composite index `idx_mti_scores_imo_year_month` on `(imo_number, year DESC, month DESC)` is created in the schema migration to support all three SELECT variants.

## Consequences

- **Easier:** No ORM schema synchronization step; queries exactly match the PRD specification; parameterized queries natively prevent SQL injection; pg's pool handles connection reuse transparently.
- **Harder:** pg returns `NUMERIC` columns as JavaScript strings — every score field in `MtiScoresRepository.mapRow()` must call `parseFloat()` explicitly. Integer columns (`id`, `year`, `month`) must also be cast with `parseInt()`. Raw SQL is less refactor-friendly if the schema changes significantly.
- **Constraint:** `DATABASE_URL` environment variable must be set before the `database.ts` module is loaded; integration tests must use dynamic `import()` inside `beforeAll` to guarantee correct module initialization order.
