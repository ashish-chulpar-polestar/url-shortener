import { Pool } from 'pg';
import { config } from './env';
import { logger } from './logger';

export const pool = new Pool({
  connectionString: config.databaseUrl,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000,
});

pool.on('connect', () => logger.info('Database connection established'));
pool.on('error', (err: Error) => logger.error('Unexpected database pool error', { error: err.message }));
