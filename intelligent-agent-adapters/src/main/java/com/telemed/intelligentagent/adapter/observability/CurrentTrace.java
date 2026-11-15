package com.telemed.intelligentagent.adapter.observability;

import org.slf4j.MDC;

/**
 * The trace context of the request being served, as TraceContextFilter leaves it in the MDC
 * (week 11 log contract, CP2). Whatever this service calls or emits next continues that trace.
 */
public final class CurrentTrace {

    public static final String TRACE_ID = "trace_id";
    public static final String SPAN_ID = "span_id";
    public static final String CORRELATION_ID = "correlation_id";

    private CurrentTrace() {
    }

    /** W3C traceparent with this service's span as the parent; null outside a traced request. */
    public static String traceparent() {
        String traceId = MDC.get(TRACE_ID);
        String spanId = MDC.get(SPAN_ID);
        return traceId == null || spanId == null ? null : "00-" + traceId + "-" + spanId + "-01";
    }

    /** The request's X-Correlation-Id; null outside a traced request. */
    public static String correlationId() {
        return MDC.get(CORRELATION_ID);
    }
}
