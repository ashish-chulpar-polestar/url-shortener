import { Request, Response, NextFunction } from 'express';
import { logger } from '../config/logger';

export class ApiError extends Error {
  constructor(
    public readonly errorCode: string,
    message: string,
    public readonly httpStatus: number
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function errorHandlerMiddleware(err: Error, req: Request, res: Response, next: NextFunction): void {
  const requestId = (res.locals.requestId as string) || 'unknown';

  if (err instanceof ApiError) {
    logger.warn('API error', { requestId, errorCode: err.errorCode, message: err.message });
    res.status(err.httpStatus).json({
      meta: {
        request_id: requestId,
        request_timestamp: new Date().toISOString(),
      },
      data: {
        error_code: err.errorCode,
        title: err.errorCode,
        message: err.message,
      },
    });
  } else {
    logger.error('Unhandled error', { requestId, error: err.message, stack: err.stack });
    res.status(500).json({
      meta: {
        request_id: requestId,
        request_timestamp: new Date().toISOString(),
      },
      data: {
        error_code: 'ERR_105',
        title: 'Internal Server Error',
        message: 'An unexpected error occurred',
      },
    });
  }
}
