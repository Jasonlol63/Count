package com.eazycount.handler;

import com.eazycount.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException exception) {
        if (exception.getPayload() != null) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", exception.getMessage() != null ? exception.getMessage() : "",
                    "payload", exception.getPayload()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "error",
                "message", exception.getMessage() != null ? exception.getMessage() : ""
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception
    ) {
        String message = "tenant_code".equals(exception.getParameterName())
                ? "Invalid login request. Please refresh the login page and try again."
                : "Missing required field: " + exception.getParameterName();
        return ResponseEntity.ok(Map.of(
                "status", "error",
                "message", message
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", "An unexpected error occurred."
        ));
    }
}
