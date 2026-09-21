package com.autotest.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class ApiResponseWriter {

    private ApiResponseWriter() {
    }

    public static void write(HttpServletResponse response, HttpServletRequest request,
                             int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(response.getWriter(), new ApiError(
                code, message, null, TraceIdFilter.traceId(request)));
    }
}
