import { PostgreSqlContainer, StartedPostgreSqlContainer } from 'testcontainers';
import supertest from 'supertest';
import fs from 'fs';
import path from 'path';
import { Pool } from 'pg';

let container: StartedPostgreSqlContainer;
let pool: Pool;
let app: any;

beforeAll(async () => {
  container = await new PostgreSqlContainer('postgres:15').start();
  process.env.DATABASE_URL = container.getConnectionUri();

  app = (await import('../../src/app')).default;

  pool = new Pool({ connectionString: container.getConnectionUri() });

  await pool.query(
    fs.readFileSync(path.join(__dirname, '../../db/migrations/V1__create_mti_scores_history.sql'), 'utf-8')
  );

  await pool.query(
    `INSERT INTO mti_scores_history (imo_number, year, month, mti_score, vessel_score, reporting_score, voyages_score, emissions_score, sanctions_score)
     VALUES
       ('9123456', 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00),
       ('9123456', 2023, 12, 80.00, 85.00, 83.50, 78.00, 82.00, 95.00),
       ('9123456', 2023, 6, 75.50, 80.00, 78.25, 72.30, 77.60, 90.00),
       ('9999998', 2024, 3, NULL, NULL, NULL, NULL, NULL, NULL)`
  );
}, 120000);

afterAll(async () => {
  await pool.end();
  await container.stop();
});

describe('GET /api/v1/vessels/:imo/mti-scores', () => {
  it('AC1: returns latest scores for a vessel', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9123456/mti-scores');
    expect(res.status).toBe(200);
    expect(res.body.data.imo_number).toBe('9123456');
    expect(res.body.data.year).toBe(2024);
    expect(res.body.data.month).toBe(1);
    expect(res.body.data.scores.mti_score).toBe(85.5);
    expect(res.body.meta.request_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });

  it('AC2: returns scores filtered by year', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9123456/mti-scores?year=2023');
    expect(res.status).toBe(200);
    expect(res.body.data.year).toBe(2023);
    expect(res.body.data.month).toBe(12);
  });

  it('AC3: returns scores filtered by year and month', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9123456/mti-scores?year=2023&month=6');
    expect(res.status).toBe(200);
    expect(res.body.data.month).toBe(6);
    expect(res.body.data.scores.mti_score).toBe(75.5);
  });

  it('AC4: returns 404 ERR_101 when IMO not found', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9999999/mti-scores');
    expect(res.status).toBe(404);
    expect(res.body.data.error_code).toBe('ERR_101');
  });

  it('AC5: returns 400 ERR_103 for invalid IMO format', async () => {
    const res = await supertest(app).get('/api/v1/vessels/123/mti-scores');
    expect(res.status).toBe(400);
    expect(res.body.data.error_code).toBe('ERR_103');
  });

  it('AC6: returns 400 ERR_102 for month without year', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9123456/mti-scores?month=6');
    expect(res.status).toBe(400);
    expect(res.body.data.error_code).toBe('ERR_102');
  });

  it('AC7: returns 400 ERR_104 for invalid month 13', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9123456/mti-scores?year=2023&month=13');
    expect(res.status).toBe(400);
    expect(res.body.data.error_code).toBe('ERR_104');
  });

  it('AC8: returns null score fields for vessel with null scores', async () => {
    const res = await supertest(app).get('/api/v1/vessels/9999998/mti-scores');
    expect(res.status).toBe(200);
    expect(res.body.data.scores.mti_score).toBeNull();
    expect(res.body.data.scores.vessel_score).toBeNull();
    expect(res.body.data.scores.reporting_score).toBeNull();
  });
});
