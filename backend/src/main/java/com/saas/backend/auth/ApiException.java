package com.saas.backend.auth;

public class ApiException extends RuntimeException {
    private final String code;
    private java.util.Map<String,Object> details = java.util.Map.of();

    public ApiException(String code, String message, java.util.Map<String,Object> details) {
        this(code, message); this.details = details;
    }
    public java.util.Map<String,Object> getDetails() { return details; }

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
