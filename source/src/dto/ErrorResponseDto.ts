import { MetaDto } from './MtiScoreResponseDto';

export interface ErrorDataDto {
  error_code: string;
  title: string;
  message: string;
}

export interface ErrorResponseDto {
  meta: MetaDto;
  data: ErrorDataDto;
}

export const ErrorCodes = {
  ERR_101: { code: 'ERR_101', title: 'Resource Not Found', httpStatus: 404 },
  ERR_102: { code: 'ERR_102', title: 'Invalid Parameters', httpStatus: 400 },
  ERR_103: { code: 'ERR_103', title: 'Invalid IMO Format', httpStatus: 400 },
  ERR_104: { code: 'ERR_104', title: 'Invalid Date Range', httpStatus: 400 },
  ERR_105: { code: 'ERR_105', title: 'Internal Server Error', httpStatus: 500 },
};
