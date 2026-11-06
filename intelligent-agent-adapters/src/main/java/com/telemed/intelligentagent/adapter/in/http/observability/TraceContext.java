package com.telemed.intelligentagent.adapter.in.http.observability;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** W3C trace context: the caller's trace id is kept, and this service opens its own span. */
public record TraceContext(String traceId, String spanId) {

    public static final String HEADER = "traceparent";

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Continues the caller's trace, or starts a new one when the header is missing or invalid. */
    public static TraceContext from(String traceparent) {
        TraceContext caller = parse(traceparent);
        return new TraceContext(caller == null ? randomHex(16) : caller.traceId(), randomHex(8));
    }

    /** The caller's own trace and span ids; null when the header is missing or invalid. */
    public static TraceContext parse(String traceparent) {
        Matcher matcher = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (matcher == null || !matcher.matches() || INVALID_TRACE_ID.equals(matcher.group(1))) {
            return null;
        }
        return new TraceContext(matcher.group(1), matcher.group(2));
    }

    String toHeader() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
