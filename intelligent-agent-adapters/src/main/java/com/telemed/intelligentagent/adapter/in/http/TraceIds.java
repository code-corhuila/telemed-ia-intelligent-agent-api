package com.telemed.intelligentagent.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/** The request's trace id: the caller's X-Correlation-Id, or a new one. */
public final class TraceIds {

    public static final String HEADER = "X-Correlation-Id";
    private static final int MAX_LENGTH = 128;

    private TraceIds() {
    }

    public static String of(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        // Bounded and printable: the value is echoed back and written to logs.
        if (header != null && !header.isBlank() && header.length() <= MAX_LENGTH
                && header.chars().allMatch(c -> c > 32 && c < 127)) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
