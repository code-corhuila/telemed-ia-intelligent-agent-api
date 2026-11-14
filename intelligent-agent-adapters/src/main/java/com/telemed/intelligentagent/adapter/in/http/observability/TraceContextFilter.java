package com.telemed.intelligentagent.adapter.in.http.observability;

import com.telemed.intelligentagent.adapter.in.http.TraceIds;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Week 11 log contract (CP2): before anything else, even security, every request gets its
 * trace context in the MDC, so each log line carries trace_id, span_id and correlation_id.
 * Only route templates are logged, never raw paths or bodies (they may carry ids or clinical text).
 * It runs inside Spring's request observation, so the ids are those of the span exported to Jaeger;
 * without a tracer it opens its own span id on the caller's trace.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceContextFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TraceContextFilter.class);

    private final ObjectProvider<Tracer> tracer;

    public TraceContextFilter() {
        this(null);
    }

    @Autowired
    public TraceContextFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TraceContext trace = traceOf(request);
        String correlationId = TraceIds.of(request);
        MDC.put(CurrentTrace.TRACE_ID, trace.traceId());
        MDC.put(CurrentTrace.SPAN_ID, trace.spanId());
        MDC.put(CurrentTrace.CORRELATION_ID, correlationId);
        response.setHeader(TraceContext.HEADER, trace.toHeader());
        response.setHeader(TraceIds.HEADER, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            // A 5xx failed the patient and needs attention; a 4xx was the caller's and was answered.
            (response.getStatus() >= 500 ? LOG.atError() : LOG.atInfo())
                    .addKeyValue("event", "http.request.completed")
                    .addKeyValue("route", route == null ? "unmatched" : route.toString())
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("request completed");
            MDC.remove(CurrentTrace.TRACE_ID);
            MDC.remove(CurrentTrace.SPAN_ID);
            MDC.remove(CurrentTrace.CORRELATION_ID);
        }
    }

    private TraceContext traceOf(HttpServletRequest request) {
        Span span = tracer == null ? null : tracer.getIfAvailable(() -> Tracer.NOOP).currentSpan();
        return span == null || span.isNoop()
                ? TraceContext.from(request.getHeader(TraceContext.HEADER))
                : new TraceContext(span.context().traceId(), span.context().spanId());
    }
}
