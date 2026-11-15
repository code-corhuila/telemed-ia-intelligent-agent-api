package com.telemed.intelligentagent.adapter.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** The trace context of the request being served, as TraceContextFilter leaves it in the MDC. */
class CurrentTraceTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void namesThisServicesSpanAsTheParentOfWhatComesNext() {
        MDC.put(CurrentTrace.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put(CurrentTrace.SPAN_ID, "00f067aa0ba902b7");
        MDC.put(CurrentTrace.CORRELATION_ID, "corr-0001");

        assertThat(CurrentTrace.traceparent()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(CurrentTrace.correlationId()).isEqualTo("corr-0001");
    }

    @Test
    void hasNothingOutsideATracedRequest() {
        assertThat(CurrentTrace.traceparent()).isNull();
        assertThat(CurrentTrace.correlationId()).isNull();
    }
}
