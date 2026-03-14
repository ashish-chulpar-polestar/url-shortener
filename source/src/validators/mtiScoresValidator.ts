const IMO_REGEX = /^[0-9]{7}$/;

export interface ValidationParams {
  imo: string;
  year: number | undefined;
  month: number | undefined;
}

export type ValidationResult = { valid: true } | { valid: false; errorCode: string; message: string };

export function validateMtiScoresRequest(params: ValidationParams): ValidationResult {
  if (!IMO_REGEX.test(params.imo)) {
    return { valid: false, errorCode: 'ERR_103', message: 'IMO number must be exactly 7 digits' };
  }

  if (params.month !== undefined && params.year === undefined) {
    return { valid: false, errorCode: 'ERR_102', message: 'Month parameter requires year parameter to be specified' };
  }

  if (params.year !== undefined && (params.year < 2000 || params.year > 2100)) {
    return { valid: false, errorCode: 'ERR_104', message: 'Year must be between 2000 and 2100' };
  }

  if (params.month !== undefined && (params.month < 1 || params.month > 12)) {
    return { valid: false, errorCode: 'ERR_104', message: 'Month must be between 1 and 12' };
  }

  return { valid: true };
}
