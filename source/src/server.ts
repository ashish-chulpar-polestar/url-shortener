import app from './app';
import { config } from './config/env';
import { logger } from './config/logger';

app.listen(config.port, () => {
  logger.info('MTI Scores API server started', { port: config.port, nodeEnv: config.nodeEnv });
});

process.on('unhandledRejection', (reason) => {
  logger.error('Unhandled rejection', { reason });
  process.exit(1);
});

process.on('uncaughtException', (err) => {
  logger.error('Uncaught exception', { error: err.message, stack: err.stack });
  process.exit(1);
});
