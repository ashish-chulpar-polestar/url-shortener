package com.polestar.mti.exception;

import com.polestar.mti.constant.ErrorCode;
import com.polestar.mti.dto.ApiResponse;
import com.polestar.mti.dto.ErrorDataDto;
import com.polestar.mti.dto.MetaDto;
import com.polestar.mti.filter.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private MetaDto buildMeta() {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        return MetaDto.of(requestId);
    }

    @ExceptionHandler(MtiException.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleMtiException(MtiException ex) {
        log.warn("MtiException errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage());
        ErrorDataDto data = ErrorDataDto.from(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(new ApiResponse<>(buildMeta(), data));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Validation failed violations={}", ex.getMessage());

        boolean isImoViolation = ex.getConstraintViolations().stream()
                .anyMatch(v -> v.getPropertyPath().toString().contains("imo"));

        ErrorCode errorCode;
        String message;
        if (isImoViolation) {
            errorCode = ErrorCode.ERR_103;
            message = "IMO number must be exactly 7 digits";
        } else {
            errorCode = ErrorCode.ERR_104;
            message = ex.getConstraintViolations().iterator().next().getMessage();
        }

        ErrorDataDto data = ErrorDataDto.from(errorCode, message);
        return ResponseEntity.badRequest().body(new ApiResponse<>(buildMeta(), data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorDataDto>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        ErrorDataDto data = ErrorDataDto.from(ErrorCode.ERR_105, "An unexpected error occurred");
        return ResponseEntity.internalServerError().body(new ApiResponse<>(buildMeta(), data));
    }
}
