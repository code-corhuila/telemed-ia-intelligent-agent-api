package com.telemed.intelligentagent.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** The request's trace id: the caller's X-Correlation-Id, or a new one; the same for the whole request. */
public final class TraceIds {

    public static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = TraceIds.class.getName();
    private static final int MAX_LENGTH = 128;
    private static final Pattern HEX_32 = Pattern.compile("[0-9a-fA-F]{32}");

    private TraceIds() {
    }

    public static String of(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String known) {
            return known;
        }
        String header = request.getHeader(HEADER);
        // Bounded and printable: the value is echoed back and written to logs.
        String traceId = header != null && !header.isBlank() && header.length() <= MAX_LENGTH
                && header.chars().allMatch(c -> c > 32 && c < 127)
                ? header
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        return traceId;
    }

    /**
     * agent-db's outbox wants a UUID: a UUID passes through, the gateway's
     * 32-hex ids are dashed, anything else maps to a stable name-based UUID.
     */
    public static UUID asUuid(String traceId) {
        try {
            return UUID.fromString(traceId);
        } catch (IllegalArgumentException notAUuid) {
            if (HEX_32.matcher(traceId).matches()) {
                return UUID.fromString(traceId.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5"));
            }
            return UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The correlation of the request being served; outside a request, a fresh one. */
    public static UUID currentAsUuid() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return asUuid(of(attributes.getRequest()));
        }
        return UUID.randomUUID();
    }
}
