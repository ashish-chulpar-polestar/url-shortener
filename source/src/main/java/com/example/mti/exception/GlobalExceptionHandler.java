package com.example.mti.exception;

import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.ApiResponse;
import com.example.mti.dto.ErrorDataDto;
import com.example.mti.dto.MetaDto;
import com.example.mti.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiResponse<ErrorDataDto>> buildErrorResponse(
            HttpServletRequest request, ErrorCode errorCode, String message) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        MetaDto meta = new MetaDto(requestId, OffsetDateTime.now(ZoneOffset.UTC).toString());
        ErrorDataDto errorData = new ErrorDataDto(errorCode.getCode(), errorCode.getTitle(), message);
        ApiResponse<ErrorDataDto> body = new ApiResponse<>(meta, errorData);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MtiException.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleMtiException(
            MtiException ex, HttpServletRequest request) {
        log.warn("Business error errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return buildErrorResponse(request, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        ConstraintViolation<?> firstViolation = ex.getConstraintViolations().iterator().next();
        log.warn("Constraint violation path={} message={}",
                firstViolation.getPropertyPath(), firstViolation.getMessage());
        String path = firstViolation.getPropertyPath().toString();
        if (path.contains("imo")) {
            return buildErrorResponse(request, ErrorCode.ERR_103, "IMO number must be exactly 7 digits");
        }
        return buildErrorResponse(request, ErrorCode.ERR_104, "Invalid year or month value");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error requestId={}", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR), ex);
        return buildErrorResponse(request, ErrorCode.ERR_105, "An internal error occurred");
    }
}
