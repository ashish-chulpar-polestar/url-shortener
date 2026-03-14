package com.example.mti.exception;

import com.example.mti.dto.ErrorResponse;
import com.example.mti.dto.ErrorResponseData;
import com.example.mti.dto.MetaDto;
import com.example.mti.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MtiApiException.class)
    public ResponseEntity<ErrorResponse> handleMtiApiException(MtiApiException ex, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = "unknown";
        }
        log.warn("MTI API error requestId={} code={} message={}", requestId, ex.getErrorCode().getCode(), ex.getDetailMessage());
        MetaDto meta = new MetaDto(requestId, Instant.now().toString());
        ErrorResponseData data = new ErrorResponseData(
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getTitle(),
                ex.getDetailMessage()
        );
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(new ErrorResponse(meta, data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = "unknown";
        }
        log.error("Unexpected error requestId={} message={}", requestId, ex.getMessage(), ex);
        MetaDto meta = new MetaDto(requestId, Instant.now().toString());
        ErrorResponseData data = new ErrorResponseData(
                ErrorCode.ERR_105.getCode(),
                ErrorCode.ERR_105.getTitle(),
                "An unexpected error occurred"
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(meta, data));
    }
}
