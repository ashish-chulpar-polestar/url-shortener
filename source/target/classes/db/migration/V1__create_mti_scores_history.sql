CREATE TABLE mti_scores_history (
    id                BIGSERIAL PRIMARY KEY,
    imo_number        VARCHAR(7)                  NOT NULL,
    year              INTEGER                     NOT NULL,
    month             INTEGER                     NOT NULL,
    mti_score         NUMERIC(5,2),
    vessel_score      NUMERIC(5,2),
    reporting_score   NUMERIC(5,2),
    voyages_score     NUMERIC(5,2),
    emissions_score   NUMERIC(5,2),
    sanctions_score   NUMERIC(5,2),
    created_at        TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC);

ALTER TABLE mti_scores_history ADD CONSTRAINT uq_imo_year_month UNIQUE (imo_number, year, month);
