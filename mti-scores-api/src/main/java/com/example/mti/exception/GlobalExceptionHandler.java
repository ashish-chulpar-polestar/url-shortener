package com.example.mti.exception;

import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.ErrorDataDto;
import com.example.mti.dto.ErrorResponseDto;
import com.example.mti.dto.MetaDto;
import com.example.mti.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private MetaDto buildMeta(WebRequest request) {
        HttpServletRequest httpRequest = ((ServletWebRequest) request).getRequest();
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        String requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new MetaDto(requestId, requestTimestamp);
    }

    @ExceptionHandler(MtiException.class)
    public ResponseEntity<ErrorResponseDto> handleMtiException(MtiException ex, WebRequest request) {
        log.warn("Business exception errorCode={} message={}", ex.getErrorCode().getCode(), ex.getMessage());
        ErrorDataDto data = new ErrorDataDto(
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getTitle(),
                ex.getMessage()
        );
        ErrorResponseDto body = new ErrorResponseDto(buildMeta(request), data);
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unexpected exception message={}", ex.getMessage(), ex);
        ErrorDataDto data = new ErrorDataDto(
                ErrorCode.ERR_105.getCode(),
                ErrorCode.ERR_105.getTitle(),
                ErrorCode.ERR_105.getDefaultMessage()
        );
        return ResponseEntity.status(500).body(new ErrorResponseDto(buildMeta(request), data));
    }
}
