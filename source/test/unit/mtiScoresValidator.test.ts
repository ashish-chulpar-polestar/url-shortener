import { validateMtiScoresRequest, ValidationParams } from '../../src/validators/mtiScoresValidator';

describe('validateMtiScoresRequest', () => {
  it('returns valid:true for 7-digit IMO, no year/month', () => {
    const params: ValidationParams = { imo: '9123456', year: undefined, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(true);
  });

  it('returns ERR_103 for IMO shorter than 7 digits', () => {
    const params: ValidationParams = { imo: '123', year: undefined, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_103');
  });

  it('returns ERR_103 for IMO longer than 7 digits', () => {
    const params: ValidationParams = { imo: '12345678', year: undefined, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_103');
  });

  it('returns ERR_103 for IMO with non-digit characters', () => {
    const params: ValidationParams = { imo: 'abc1234', year: undefined, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_103');
  });

  it('returns ERR_102 for month without year', () => {
    const params: ValidationParams = { imo: '9123456', year: undefined, month: 6 };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_102');
  });

  it('returns ERR_104 for year below 2000', () => {
    const params: ValidationParams = { imo: '9123456', year: 1999, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_104');
  });

  it('returns ERR_104 for year above 2100', () => {
    const params: ValidationParams = { imo: '9123456', year: 2101, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_104');
  });

  it('returns ERR_104 for month value 13', () => {
    const params: ValidationParams = { imo: '9123456', year: 2023, month: 13 };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_104');
  });

  it('returns ERR_104 for month value 0', () => {
    const params: ValidationParams = { imo: '9123456', year: 2023, month: 0 };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(false);
    expect((result as any).errorCode).toBe('ERR_104');
  });

  it('returns valid:true for valid year without month', () => {
    const params: ValidationParams = { imo: '9123456', year: 2023, month: undefined };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(true);
  });

  it('returns valid:true for valid year and month', () => {
    const params: ValidationParams = { imo: '9123456', year: 2023, month: 6 };
    const result = validateMtiScoresRequest(params);
    expect(result.valid).toBe(true);
  });
});
