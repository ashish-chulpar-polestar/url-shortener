CREATE TABLE IF NOT EXISTS mti_scores_history (
  id BIGSERIAL PRIMARY KEY,
  imo_number VARCHAR(7) NOT NULL,
  year INTEGER NOT NULL,
  month INTEGER NOT NULL,
  mti_score NUMERIC(5,2),
  vessel_score NUMERIC(5,2),
  reporting_score NUMERIC(5,2),
  voyages_score NUMERIC(5,2),
  emissions_score NUMERIC(5,2),
  sanctions_score NUMERIC(5,2),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_imo_format CHECK (imo_number ~ '^[0-9]{7}$'),
  CONSTRAINT chk_month_range CHECK (month BETWEEN 1 AND 12),
  CONSTRAINT chk_year_range CHECK (year BETWEEN 2000 AND 2100)
);

CREATE INDEX IF NOT EXISTS idx_mti_scores_imo_year_month ON mti_scores_history (imo_number, year DESC, month DESC);
