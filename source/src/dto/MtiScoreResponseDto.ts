export interface MetaDto {
  request_id: string;
  request_timestamp: string;
}

export interface ScoresDto {
  mti_score: number | null;
  vessel_score: number | null;
  reporting_score: number | null;
  voyages_score: number | null;
  emissions_score: number | null;
  sanctions_score: number | null;
}

export interface MtiScoreDataDto {
  imo_number: string;
  year: number;
  month: number;
  scores: ScoresDto;
  metadata: {
    created_at: string;
    updated_at: string;
  };
}

export interface MtiScoreResponseDto {
  meta: MetaDto;
  data: MtiScoreDataDto;
}
