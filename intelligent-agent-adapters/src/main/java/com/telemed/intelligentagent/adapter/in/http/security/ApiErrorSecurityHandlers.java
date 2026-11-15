package com.telemed.intelligentagent.adapter.in.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.in.http.ApiErrorResponse;
import com.telemed.intelligentagent.adapter.in.http.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401 and 403 written with the common error envelope. The messages never
 * say why a token was rejected: that detail would only help an attacker.
 */
public final class ApiErrorSecurityHandlers {

    private final ObjectMapper objectMapper;

    public ApiErrorSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> write(request, response, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Missing or invalid credentials.");
    }

    public AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(request, response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "This operation is not available for your role.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String error, String message) throws IOException {
        String traceId = TraceIds.of(request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(TraceIds.HEADER, traceId);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(error, message, traceId));
    }
}
