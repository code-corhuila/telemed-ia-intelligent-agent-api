package com.telemed.intelligentagent.adapter.in.http.observability;

import com.telemed.intelligentagent.adapter.in.http.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TraceContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TraceContext trace = TraceContext.from(request.getHeader(TraceContext.HEADER));
        String correlationId = TraceIds.of(request);
        MDC.put("trace_id", trace.traceId());
        MDC.put("span_id", trace.spanId());
        MDC.put("correlation_id", correlationId);
        response.setHeader(TraceContext.HEADER, trace.toHeader());
        response.setHeader(TraceIds.HEADER, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.atInfo()
                    .addKeyValue("event", "http.request.completed")
                    .addKeyValue("route", route == null ? "unmatched" : route.toString())
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("request completed");
            MDC.remove("trace_id");
            MDC.remove("span_id");
            MDC.remove("correlation_id");
        }
    }
}
