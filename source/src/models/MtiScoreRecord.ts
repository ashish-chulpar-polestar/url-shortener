export interface MtiScoreRecord {
  id: number;
  imo_number: string;
  year: number;
  month: number;
  mti_score: number | null;
  vessel_score: number | null;
  reporting_score: number | null;
  voyages_score: number | null;
  emissions_score: number | null;
  sanctions_score: number | null;
  created_at: Date;
  updated_at: Date;
}
