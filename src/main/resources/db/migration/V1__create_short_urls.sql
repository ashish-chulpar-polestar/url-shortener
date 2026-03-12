CREATE TABLE short_urls (
    id          BIGSERIAL                   PRIMARY KEY,
    code        VARCHAR(6)                  NOT NULL,
    original_url TEXT                       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE   NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE   NOT NULL,
    CONSTRAINT uq_short_urls_code UNIQUE (code)
);

CREATE INDEX idx_short_urls_code    ON short_urls (code);
CREATE INDEX idx_short_urls_expires ON short_urls (expires_at);
