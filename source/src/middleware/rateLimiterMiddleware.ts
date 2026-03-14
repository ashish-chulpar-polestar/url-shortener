import rateLimit from 'express-rate-limit';
import { Request, Response } from 'express';
import { config } from '../config/env';

export const rateLimiterMiddleware = rateLimit({
  windowMs: config.rateLimitWindowMs,
  max: config.rateLimitMaxRequests,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req: Request): string => (req.headers['x-api-key'] as string) || req.ip || 'unknown',
  handler: (req: Request, res: Response): void => {
    const requestId = (res.locals.requestId as string) || 'unknown';
    res.status(429).json({
      meta: {
        request_id: requestId,
        request_timestamp: new Date().toISOString(),
      },
      data: {
        error_code: 'ERR_429',
        title: 'Too Many Requests',
        message: 'Rate limit exceeded. Maximum 100 requests per minute.',
      },
    });
  },
});
