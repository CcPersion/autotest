package com.autotest.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import com.autotest.platform.secret.SecretCryptoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiDomainException.class)
    ResponseEntity<ApiError> domain(HttpServletRequest request, ApiDomainException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), exception.details(),
                        TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler(SecretCryptoException.class)
    ResponseEntity<ApiError> crypto(HttpServletRequest request, SecretCryptoException ignored) {
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_CRYPTO_ERROR", "密钥加解密失败");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "请求格式不正确",
                        Map.of("fieldErrors", List.of(Map.of("path", "$", "code", "INVALID_JSON",
                                "message", "请求格式不正确"))), TraceIdFilter.traceId(request)));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> notFound(HttpServletRequest request, Exception ignored) {
        return error(request, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidArgument(HttpServletRequest request, IllegalArgumentException ignored) {
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不正确");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(HttpServletRequest request, Exception ignored) {
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误");
    }

    private ResponseEntity<ApiError> error(HttpServletRequest request, HttpStatus status,
                                           String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, null, TraceIdFilter.traceId(request)));
    }
}
