import winston from 'winston';
import { config } from './env';

export const logger = winston.createLogger({
  level: config.nodeEnv === 'production' ? 'info' : 'debug',
  format:
    config.nodeEnv === 'production'
      ? winston.format.json()
      : winston.format.combine(winston.format.colorize(), winston.format.simple()),
  transports: [new winston.transports.Console()],
});

export function createChildLogger(requestId: string): winston.Logger {
  return logger.child({ requestId });
}
