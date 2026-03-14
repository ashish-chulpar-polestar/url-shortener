import express, { Application } from 'express';
import { requestIdMiddleware } from './middleware/requestIdMiddleware';
import { rateLimiterMiddleware } from './middleware/rateLimiterMiddleware';
import { errorHandlerMiddleware } from './middleware/errorHandlerMiddleware';
import { mtiScoresRouter } from './controllers/MtiScoresController';

const app: Application = express();

app.use(express.json());
app.use(requestIdMiddleware);
app.use(rateLimiterMiddleware);
app.use('/api/v1', mtiScoresRouter);
app.use(errorHandlerMiddleware);

export default app;
