import { Router, Request, Response, NextFunction } from 'express';
import { MtiScoresService } from '../services/MtiScoresService';
import { MtiScoresRepository } from '../repositories/MtiScoresRepository';
import { pool } from '../config/database';
import { validateMtiScoresRequest } from '../validators/mtiScoresValidator';
import { ApiError } from '../middleware/errorHandlerMiddleware';
import { ErrorCodes } from '../dto/ErrorResponseDto';
import { logger } from '../config/logger';

const repository = new MtiScoresRepository(pool);
const service = new MtiScoresService(repository);
const log = logger.child({ controller: 'MtiScoresController' });

export const mtiScoresRouter = Router();

mtiScoresRouter.get('/vessels/:imo/mti-scores', async (req: Request, res: Response, next: NextFunction): Promise<void> => {
  const requestTimestamp = new Date().toISOString();
  const requestId = req.requestId;
  const imo = req.params.imo;
  const rawYear = req.query.year as string | undefined;
  const rawMonth = req.query.month as string | undefined;
  const year = rawYear !== undefined ? parseInt(rawYear, 10) : undefined;
  const month = rawMonth !== undefined ? parseInt(rawMonth, 10) : undefined;

  log.info('MTI scores request received', { requestId, imo, year, month });

  if (rawYear !== undefined && isNaN(year as number)) {
    return next(new ApiError('ERR_104', 'Year must be a valid integer', 400));
  }

  if (rawMonth !== undefined && isNaN(month as number)) {
    return next(new ApiError('ERR_104', 'Month must be a valid integer', 400));
  }

  const validation = validateMtiScoresRequest({ imo, year, month });
  if (!validation.valid) {
    const ec = ErrorCodes[validation.errorCode as keyof typeof ErrorCodes];
    return next(new ApiError(validation.errorCode, validation.message, ec?.httpStatus || 400));
  }

  try {
    const data = await service.getMtiScores(imo, year, month);
    res.status(200).json({
      meta: {
        request_id: requestId,
        request_timestamp: requestTimestamp,
      },
      data,
    });
  } catch (err) {
    next(err);
  }
});
