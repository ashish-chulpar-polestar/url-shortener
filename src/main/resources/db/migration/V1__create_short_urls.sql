CREATE TABLE short_urls (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL UNIQUE,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_short_urls_code ON short_urls (code);
