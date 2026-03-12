The PRD has been written to `artifacts/prd.md`. Here's a summary of what was produced:

---

## PRD Overview — URL Shortener Service

The document covers all 9 required sections:

### Key decisions captured

**Functional Requirements (23 total)**
- `POST /shorten`: validates HTTP/HTTPS URLs, returns `{ code, shortUrl }`, generates a random 6-char alphanumeric code, always creates a new code even for duplicate URLs, retries on collision
- `GET /{code}`: 302 redirect when valid, 404 when unknown, 410 Gone when expired — expiry checked against UTC server time
- Schema: `short_urls` table with `id`, `code` (unique index), `original_url`, `created_at`, `expires_at`
- Flyway/Liquibase for schema migrations

**Non-Functional Requirements**
- `GET /{code}` p95 < 100 ms; `POST /shorten` p95 < 300 ms
- SQL injection prevention via parameterized queries / ORM
- `code` path param sanitized to `[A-Za-z0-9]{6}` before DB lookup
- Stateless app tier for horizontal scaling
- Actuator health endpoints for liveness/readiness probes

**5 Implementation Risks with Mitigations**
1. Short code collision under high throughput — DB-level unique constraint + retry loop + monitor namespace utilization
2. Expired record accumulation — index on `expires_at` + planned cleanup job in v1.1
3. `shortUrl` host misconfiguration behind proxies — configurable `app.base-url` property
4. Database unavailability — HikariCP timeouts + `@ControllerAdvice` mapping `DataAccessException` to 503
5. Open redirect abuse via non-HTTP schemes — enforce `http://`/`https://` scheme validation at intake
