package com.eazycount.handler;

import com.eazycount.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /*
     * Shape matches what nearly every controller used to hand-build per endpoint
     * (success/message/data) before their duplicate try/catch + error() helpers were
     * removed in favor of this single handler.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("success", false);
        body.put("message", exception.getMessage() != null ? exception.getMessage() : "");
        body.put("data", exception.getPayload());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception
    ) {
        String message = "tenant_code".equals(exception.getParameterName())
                ? "Invalid login request. Please refresh the login page and try again."
                : "Missing required field: " + exception.getParameterName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpectedException(Exception exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("success", false);
        body.put("message", "An unexpected error occurred.");
        body.put("data", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
