package com.telemed.intelligentagent.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Duration;

/**
 * Week 11 metric catalog (CP4): agent_requests_total (traffic, errors) and
 * agent_request_duration_seconds (latency). Labels are route templates only:
 * a raw path would create one series per conversation id and expose it.
 */
public class AgentRequestMetricsFilter extends OncePerRequestFilter {

    private static final String AGENT_API = "/api/v1/agent";
    // Starting or finishing is a MongoDB write (< 0.5 s); a message waits on the AI model (2-4 s).
    private static final Duration[] BUCKETS = {
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)};

    private final MeterRegistry registry;

    public AgentRequestMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AGENT_API);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = template == null ? "unmatched" : template.toString();
            Counter.builder("agent.requests")
                    .description("Pre-consultation HTTP requests by outcome")
                    .tags("route", route, "method", request.getMethod(), "outcome", outcome(response.getStatus()))
                    .register(registry)
                    .increment();
            Timer.builder("agent.request.duration")
                    .description("Pre-consultation HTTP latency")
                    .tags("route", route, "method", request.getMethod())
                    .serviceLevelObjectives(BUCKETS)
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    static String outcome(int status) {
        if (status >= 500) {
            return "error";
        }
        return status >= 400 ? "client_error" : "success";
    }
}
