import { MtiScoresRepository } from '../repositories/MtiScoresRepository';
import { MtiScoreDataDto } from '../dto/MtiScoreResponseDto';
import { ApiError } from '../middleware/errorHandlerMiddleware';
import { logger } from '../config/logger';

export class MtiScoresService {
  private static readonly log = logger.child({ class: 'MtiScoresService' });

  constructor(private readonly repository: MtiScoresRepository) {}

  async getMtiScores(imoNumber: string, year?: number, month?: number): Promise<MtiScoreDataDto> {
    MtiScoresService.log.info('Getting MTI scores', { imoNumber, year, month });

    try {
      let record;
      if (year !== undefined && month !== undefined) {
        record = await this.repository.findByYearAndMonth(imoNumber, year, month);
      } else if (year !== undefined) {
        record = await this.repository.findByYear(imoNumber, year);
      } else {
        record = await this.repository.findLatest(imoNumber);
      }

      if (record === null) {
        MtiScoresService.log.warn('No MTI scores found', { imoNumber, year, month });
        throw new ApiError('ERR_101', `No MTI scores found for IMO ${imoNumber}`, 404);
      }

      return {
        imo_number: record.imo_number,
        year: record.year,
        month: record.month,
        scores: {
          mti_score: record.mti_score,
          vessel_score: record.vessel_score,
          reporting_score: record.reporting_score,
          voyages_score: record.voyages_score,
          emissions_score: record.emissions_score,
          sanctions_score: record.sanctions_score,
        },
        metadata: {
          created_at: record.created_at.toISOString(),
          updated_at: record.updated_at.toISOString(),
        },
      };
    } catch (err) {
      if (err instanceof ApiError) {
        throw err;
      }
      MtiScoresService.log.error('Database error in getMtiScores', { imoNumber, error: (err as Error).message });
      throw new ApiError('ERR_105', 'Internal Server Error', 500);
    }
  }
}
