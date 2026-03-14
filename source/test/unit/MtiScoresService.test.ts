import { MtiScoresService } from '../../src/services/MtiScoresService';
import { MtiScoresRepository } from '../../src/repositories/MtiScoresRepository';
import { ApiError } from '../../src/middleware/errorHandlerMiddleware';
import { MtiScoreRecord } from '../../src/models/MtiScoreRecord';

jest.mock('../../src/repositories/MtiScoresRepository');

const mockRecord: MtiScoreRecord = {
  id: 1,
  imo_number: '9123456',
  year: 2024,
  month: 1,
  mti_score: 85.50,
  vessel_score: 90.00,
  reporting_score: 88.75,
  voyages_score: 82.30,
  emissions_score: 87.60,
  sanctions_score: 100.00,
  created_at: new Date('2024-01-01T00:00:00Z'),
  updated_at: new Date('2024-01-01T00:00:00Z'),
};

describe('MtiScoresService', () => {
  let mockRepository: jest.Mocked<MtiScoresRepository>;
  let service: MtiScoresService;

  beforeEach(() => {
    jest.clearAllMocks();
    mockRepository = new MtiScoresRepository(null as any) as jest.Mocked<MtiScoresRepository>;
    service = new MtiScoresService(mockRepository);
  });

  it('calls findLatest when no year or month', async () => {
    jest.spyOn(mockRepository, 'findLatest').mockResolvedValue(mockRecord);
    const result = await service.getMtiScores('9123456');
    expect(mockRepository.findLatest).toHaveBeenCalledWith('9123456');
    expect(result.imo_number).toBe('9123456');
    expect(result.scores.mti_score).toBe(85.50);
  });

  it('calls findByYear when year provided without month', async () => {
    jest.spyOn(mockRepository, 'findByYear').mockResolvedValue(mockRecord);
    const result = await service.getMtiScores('9123456', 2023);
    expect(mockRepository.findByYear).toHaveBeenCalledWith('9123456', 2023);
    expect(result.imo_number).toBe('9123456');
  });

  it('calls findByYearAndMonth when both year and month provided', async () => {
    jest.spyOn(mockRepository, 'findByYearAndMonth').mockResolvedValue(mockRecord);
    const result = await service.getMtiScores('9123456', 2023, 6);
    expect(mockRepository.findByYearAndMonth).toHaveBeenCalledWith('9123456', 2023, 6);
    expect(result.imo_number).toBe('9123456');
  });

  it('throws ApiError ERR_101 when record not found', async () => {
    jest.spyOn(mockRepository, 'findLatest').mockResolvedValue(null);
    try {
      await service.getMtiScores('9999999');
      fail('Expected error to be thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).errorCode).toBe('ERR_101');
    }
  });

  it('throws ApiError ERR_105 on database error', async () => {
    jest.spyOn(mockRepository, 'findLatest').mockRejectedValue(new Error('Connection refused'));
    try {
      await service.getMtiScores('9123456');
      fail('Expected error to be thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).errorCode).toBe('ERR_105');
    }
  });

  it('returns null score fields as null in response', async () => {
    jest.spyOn(mockRepository, 'findLatest').mockResolvedValue({ ...mockRecord, mti_score: null });
    const result = await service.getMtiScores('9123456');
    expect(result.scores.mti_score).toBeNull();
  });
});
