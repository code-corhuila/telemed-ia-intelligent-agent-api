package com.telemed.intelligentagent.app.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** Week 11 metric catalog (CP4, 04-metrics.md): traffic, errors and latency per route template. */
class AgentRequestMetricsFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentRequestMetricsFilter filter = new AgentRequestMetricsFilter(registry);

    @Test
    void countsAndTimesEachRequestByRouteTemplateAndOutcome() throws Exception {
        handle("/api/v1/agent/conversations/{id}/messages", "/api/v1/agent/conversations/abc/messages", 200);
        handle("/api/v1/agent/conversations/{id}/messages", "/api/v1/agent/conversations/def/messages", 503);
        handle("/api/v1/agent/conversations", "/api/v1/agent/conversations", 404);

        assertThat(count("/api/v1/agent/conversations/{id}/messages", "success")).isEqualTo(1);
        assertThat(count("/api/v1/agent/conversations/{id}/messages", "error")).isEqualTo(1);
        assertThat(count("/api/v1/agent/conversations", "client_error")).isEqualTo(1);
        assertThat(registry.get("agent.request.duration")
                .tags("route", "/api/v1/agent/conversations/{id}/messages", "method", "POST").timer().count())
                .isEqualTo(2);
    }

    @Test
    void neverUsesTheRawPathAsALabel() throws Exception {
        handle(null, "/api/v1/agent/conversations/3f1c2b7a-0000-4000-8000-000000000001", 401);

        // One series per id would explode cardinality and expose patients' ids.
        assertThat(count("unmatched", "client_error")).isEqualTo(1);
        assertThat(registry.find("agent.requests").tag("route", "/api/v1/agent/conversations/3f1c2b7a-0000-4000-8000-000000000001")
                .counter()).isNull();
    }

    @Test
    void measuresOnlyTheAgentApi() throws Exception {
        handle(null, "/actuator/health", 200);

        assertThat(registry.find("agent.requests").counters()).isEmpty();
    }

    private void handle(String template, String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            if (template != null) {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
            }
            ((MockHttpServletResponse) res).setStatus(status);
        });
    }

    private double count(String route, String outcome) {
        return registry.get("agent.requests").tags("route", route, "method", "POST", "outcome", outcome)
                .counter().count();
    }
}
