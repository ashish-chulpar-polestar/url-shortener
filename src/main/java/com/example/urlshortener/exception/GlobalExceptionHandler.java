package com.example.urlshortener.exception;

import com.example.urlshortener.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShortCodeNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ShortCodeNotFoundException ex) {
        return new ErrorResponse("Short code not found");
    }

    @ExceptionHandler(ShortCodeExpiredException.class)
    @ResponseStatus(HttpStatus.GONE)
    public ErrorResponse handleExpired(ShortCodeExpiredException ex) {
        return new ErrorResponse("Short URL has expired");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return new ErrorResponse(message);
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleDatabase(DataAccessException ex) {
        logger.error("Database error", ex);
        return new ErrorResponse("Service temporarily unavailable");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleCodeExhaustion(IllegalStateException ex) {
        logger.error("Code generation exhausted", ex);
        return new ErrorResponse("Service temporarily unavailable");
    }
}
