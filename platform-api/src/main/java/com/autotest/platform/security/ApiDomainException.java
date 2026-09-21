package com.autotest.platform.security;

public class ApiDomainException extends RuntimeException {

    private final int status;
    private final String code;
    private final Object details;

    public ApiDomainException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiDomainException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
