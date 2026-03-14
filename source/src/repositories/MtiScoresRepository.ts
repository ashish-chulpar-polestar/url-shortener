import { Pool } from 'pg';
import { MtiScoreRecord } from '../models/MtiScoreRecord';
import { logger } from '../config/logger';

export class MtiScoresRepository {
  private static readonly log = logger.child({ class: 'MtiScoresRepository' });

  constructor(private readonly pool: Pool) {}

  async findLatest(imoNumber: string): Promise<MtiScoreRecord | null> {
    MtiScoresRepository.log.debug('Executing findLatest', { imoNumber });
    const result = await this.pool.query(
      'SELECT * FROM mti_scores_history WHERE imo_number = $1 ORDER BY year DESC, month DESC LIMIT 1',
      [imoNumber]
    );
    return result.rows.length > 0 ? this.mapRow(result.rows[0]) : null;
  }

  async findByYear(imoNumber: string, year: number): Promise<MtiScoreRecord | null> {
    MtiScoresRepository.log.debug('Executing findByYear', { imoNumber, year });
    const result = await this.pool.query(
      'SELECT * FROM mti_scores_history WHERE imo_number = $1 AND year = $2 ORDER BY month DESC LIMIT 1',
      [imoNumber, year]
    );
    return result.rows.length > 0 ? this.mapRow(result.rows[0]) : null;
  }

  async findByYearAndMonth(imoNumber: string, year: number, month: number): Promise<MtiScoreRecord | null> {
    MtiScoresRepository.log.debug('Executing findByYearAndMonth', { imoNumber, year, month });
    const result = await this.pool.query(
      'SELECT * FROM mti_scores_history WHERE imo_number = $1 AND year = $2 AND month = $3 LIMIT 1',
      [imoNumber, year, month]
    );
    return result.rows.length > 0 ? this.mapRow(result.rows[0]) : null;
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private mapRow(row: any): MtiScoreRecord {
    return {
      id: parseInt(row.id),
      imo_number: row.imo_number,
      year: parseInt(row.year),
      month: parseInt(row.month),
      mti_score: row.mti_score !== null ? parseFloat(row.mti_score) : null,
      vessel_score: row.vessel_score !== null ? parseFloat(row.vessel_score) : null,
      reporting_score: row.reporting_score !== null ? parseFloat(row.reporting_score) : null,
      voyages_score: row.voyages_score !== null ? parseFloat(row.voyages_score) : null,
      emissions_score: row.emissions_score !== null ? parseFloat(row.emissions_score) : null,
      sanctions_score: row.sanctions_score !== null ? parseFloat(row.sanctions_score) : null,
      created_at: new Date(row.created_at),
      updated_at: new Date(row.updated_at),
    };
  }
}
