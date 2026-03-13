# Requirement: URL Shortener Service

## Summary
Build a simple URL shortener service that converts long URLs into short codes
and redirects users to the original URL when the short code is accessed.

## Details
Users submit a long URL and receive a short code (e.g. `abc123`).
Visiting `/<code>` redirects the user to the original URL.
Short codes are 6 characters, alphanumeric, randomly generated.
Each short URL expires after 30 days.

## Endpoints
- `POST /shorten` — accepts `{ "url": "https://..." }`, returns `{ "code": "abc123", "shortUrl": "http://host/abc123" }`
- `GET /{code}` — redirects to the original URL (HTTP 302), returns 404 if not found or expired

## Tech
- Spring Boot REST API
- Store mappings in PostgreSQL (`id`, `code`, `original_url`, `created_at`, `expires_at`)
- Return 410 Gone if the short URL has expired

## Acceptance Criteria
- Short codes are unique and exactly 6 alphanumeric characters
- Duplicate long URLs get a new code each time
- Expired codes return 410, unknown codes return 404
- Unit tests for the shortening logic and expiry check
