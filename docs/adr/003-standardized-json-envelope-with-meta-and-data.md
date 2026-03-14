# ADR-003: Standardized JSON Response Envelope with meta and data Sections

**Status:** Accepted
**Date:** 2026-03-14
**Feature:** MTI Scores API - Product Requirements Document

## Context

The PRD mandates a consistent response structure for both success and error cases: a top-level `meta` object containing `request_id` (UUID v4) and `request_timestamp` (ISO 8601), and a `data` object containing either the scores payload or an error object with `error_code`, `title`, and `message`. This is an explicit API design requirement, not an optional choice. The key design decisions within this constraint were: (1) where to generate `request_id` — in a middleware (once per request, shared across all handlers) vs. in each handler; (2) where to stamp `request_timestamp` — as early as possible to reflect true request receipt time; (3) how to make `request_id` available to the error handler which runs outside the normal request path.

## Decision

Generate `request_id` as a UUID v4 in `requestIdMiddleware` (registered first in the Express middleware chain) and attach it to both `req.requestId` (via TypeScript declaration merging in `custom.d.ts`) and `res.locals.requestId`. The controller reads `req.requestId` and captures `request_timestamp = new Date().toISOString()` at the top of the handler before any async work. The error handler (`errorHandlerMiddleware`) reads `res.locals.requestId` — since `req` is not reliably available in error handlers — to include the same `request_id` in error envelopes. All five application error codes (ERR_101–ERR_105) plus ERR_429 are mapped to HTTP status codes in the `ErrorCodes` constant object in `source/src/dto/ErrorResponseDto.ts`.

## Consequences

- **Easier:** Every response (success, validation error, not-found, server error, rate limit) carries a traceable `request_id`, enabling log correlation across all layers. The `ApiError` class carries `errorCode` and `httpStatus` together, so the error handler needs no lookup table to determine the status code.
- **Harder:** The Express `Request` type must be augmented via a `custom.d.ts` ambient declaration to avoid TypeScript errors on `req.requestId`. `res.locals.requestId` must be set in `requestIdMiddleware` (before the rate limiter) so the rate limiter's custom 429 handler can also include a `request_id`.
- **Constraint:** The `request_timestamp` must be captured before `await service.getMtiScores(...)` in the controller; capturing it inside a try block would record a later time if async operations are slow.
