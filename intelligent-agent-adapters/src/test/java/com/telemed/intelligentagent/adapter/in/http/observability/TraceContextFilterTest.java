package com.telemed.intelligentagent.adapter.in.http.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Week 11 log contract (CP2): every line of a request carries its trace_id, span_id and correlation_id. */
class TraceContextFilterTest {

    private static final String CALLER_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final TraceContextFilter filter = new TraceContextFilter();
    private final Map<String, String> seenByTheRequest = new HashMap<>();
    private final FilterChain chain = (request, response) -> seenByTheRequest.putAll(MDC.getCopyOfContextMap());

    @Test
    void continuesTheCallersTraceWithItsOwnSpan() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/conversations");
        request.addHeader("traceparent", "00-" + CALLER_TRACE + "-00f067aa0ba902b7-01");
        request.addHeader("X-Correlation-Id", "corr-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(seenByTheRequest).containsEntry("trace_id", CALLER_TRACE)
                .containsEntry("correlation_id", "corr-0001");
        assertThat(seenByTheRequest.get("span_id")).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(response.getHeader("traceparent"))
                .isEqualTo("00-" + CALLER_TRACE + "-" + seenByTheRequest.get("span_id") + "-01");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-0001");
    }

    @Test
    void startsANewTraceWhenTheCallerSentNoneOrAnInvalidOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/conversations");
        request.addHeader("traceparent", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenByTheRequest.get("trace_id")).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(seenByTheRequest.get("correlation_id")).isNotBlank();
    }

    @Test
    void leavesNothingBehindForTheNextRequestOnTheSameThread() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(MDC.get("trace_id")).isNull();
        assertThat(MDC.get("correlation_id")).isNull();
    }
}
