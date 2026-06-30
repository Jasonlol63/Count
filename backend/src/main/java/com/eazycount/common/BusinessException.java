package com.eazycount.common;

import java.util.Map;

public class BusinessException extends RuntimeException {

    private Map<String, Object> payload;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Map<String, Object> payload) {
        super(message);
        this.payload = payload;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
