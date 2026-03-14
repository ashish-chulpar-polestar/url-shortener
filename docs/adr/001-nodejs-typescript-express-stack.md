# ADR-001: Node.js/TypeScript with Express as the API Runtime and Framework

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD requires a new greenfield REST API to serve MTI scores. No existing codebase or mandated language was specified. The PRD implementation notes reference `uuid.v4()` — the idiomatic Node.js UUID generation call — suggesting a JavaScript/TypeScript runtime. The API is a simple read-only endpoint with no complex computation, making a lightweight framework preferable over a full application server. Alternatives considered were Java/Spring Boot (heavier startup, more ceremony for a single endpoint), Python/FastAPI (good option but less alignment with the uuid.v4() reference), and Go/net/http (fast but less ecosystem support for the required middleware stack).

## Decision

Use **Node.js 20 LTS** with **TypeScript 5.x** as the language and runtime, and **Express 4.x** as the HTTP framework. TypeScript is compiled with `strict: true` and `esModuleInterop: true`. The project is scaffolded under a `source/` directory with `src/` for application code and `test/` for tests. `ts-node` is used for development; `tsc` compiles to `dist/` for production.

## Consequences

- **Easier:** Fast startup time; low memory footprint; uuid, winston, and express-rate-limit all have first-class Node.js packages; supertest + jest provide a mature testing stack without spawning a server process.
- **Harder:** TypeScript strict mode requires explicit handling of `number | null` for all six nullable score fields and type augmentation (`custom.d.ts`) for the `req.requestId` property. Node.js module caching semantics require care in integration tests (dynamic `import()` after setting `process.env.DATABASE_URL`).
- **Constraint:** The `pg` library returns `NUMERIC` columns as strings; all score fields must be explicitly cast with `parseFloat()` in `MtiScoresRepository.mapRow()`.
