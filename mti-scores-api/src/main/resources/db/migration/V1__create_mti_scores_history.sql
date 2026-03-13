CREATE TABLE mti_scores_history (
    id            BIGSERIAL PRIMARY KEY,
    imo_number    VARCHAR(7) NOT NULL,
    year          INTEGER NOT NULL,
    month         INTEGER NOT NULL,
    mti_score     NUMERIC(5,2) NULL,
    vessel_score  NUMERIC(5,2) NULL,
    reporting_score NUMERIC(5,2) NULL,
    voyages_score NUMERIC(5,2) NULL,
    emissions_score NUMERIC(5,2) NULL,
    sanctions_score NUMERIC(5,2) NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

ALTER TABLE mti_scores_history
    ADD CONSTRAINT uq_mti_scores_imo_year_month UNIQUE (imo_number, year, month);

CREATE INDEX idx_mti_scores_imo_year_month
    ON mti_scores_history (imo_number, year DESC, month DESC);
