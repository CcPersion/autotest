package com.autotest.platform.security;

public record ApiError(String code, String message, Object details, String traceId) {
}
