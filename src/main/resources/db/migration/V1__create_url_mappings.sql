CREATE TABLE url_mappings (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL,
    original_url TEXT        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_url_mappings_code UNIQUE (code)
);

CREATE UNIQUE INDEX idx_url_mappings_code ON url_mappings (code);
