CREATE TABLE url_mappings (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)    NOT NULL UNIQUE,
    original_url VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_url_mappings_code ON url_mappings (code);
