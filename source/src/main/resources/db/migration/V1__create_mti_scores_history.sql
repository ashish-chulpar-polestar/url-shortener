CREATE TABLE mti_scores_history (
    id             BIGSERIAL PRIMARY KEY,
    imo_number     VARCHAR(7)                  NOT NULL,
    year           INTEGER                     NOT NULL,
    month          INTEGER                     NOT NULL,
    mti_score      NUMERIC(10, 2),
    vessel_score   NUMERIC(10, 2),
    reporting_score NUMERIC(10, 2),
    voyages_score  NUMERIC(10, 2),
    emissions_score NUMERIC(10, 2),
    sanctions_score NUMERIC(10, 2),
    created_at     TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_mti_scores_imo_year_month_unique
    ON mti_scores_history (imo_number, year, month);

CREATE INDEX idx_mti_scores_imo_year_month
    ON mti_scores_history (imo_number, year DESC, month DESC);
