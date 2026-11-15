package com.telemed.intelligentagent.adapter.out.messaging;

import com.telemed.intelligentagent.adapter.CapturedLogs;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Week 11 (CP3): the relay publishes each event inside the trace of the request that recorded it. */
class TracingIntegrationEventPublisherTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String PATIENT = UUID.randomUUID().toString();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void publishesInTheTraceAndWithTheCorrelationIdOfTheRequestThatRecordedTheEvent() {
        Map<String, String> seen = new HashMap<>();
        OutboxEvent event = event(TRACEPARENT);

        new TracingIntegrationEventPublisher(published -> seen.putAll(MDC.getCopyOfContextMap())).publish(event);

        assertThat(seen.get(CurrentTrace.TRACE_ID)).isEqualTo(TRACE_ID);
        assertThat(seen.get(CurrentTrace.SPAN_ID)).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(seen.get(CurrentTrace.CORRELATION_ID)).isEqualTo(event.correlationId());
    }

    @Test
    void startsANewTraceForAnEventWithoutTraceContext() {
        Map<String, String> seen = new HashMap<>();

        new TracingIntegrationEventPublisher(published -> seen.putAll(MDC.getCopyOfContextMap())).publish(event(null));

        assertThat(seen.get(CurrentTrace.TRACE_ID)).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
    }

    @Test
    void logsThePublicationWithTheEventIdentityOnly() {
        OutboxEvent event = event(TRACEPARENT);

        try (CapturedLogs logs = CapturedLogs.of(TracingIntegrationEventPublisher.class)) {
            new TracingIntegrationEventPublisher(published -> { }).publish(event);

            List<Map<String, Object>> published = logs.events("outbox.event.published");
            assertThat(published).singleElement().satisfies(line -> {
                assertThat(line).containsEntry("event_id", event.id().toString())
                        .containsEntry("event_type", "PreConsultationSummaryGenerated")
                        .containsEntry("level", "INFO")
                        .containsKey("duration_ms");
            });
            assertThat(logs.everything()).doesNotContain(PATIENT);
        }
    }

    @Test
    void logsARefusedPublicationAndLetsTheRelayKeepTheEventPending() {
        EventPublicationException refused = new EventPublicationException("refused", null);

        try (CapturedLogs logs = CapturedLogs.of(TracingIntegrationEventPublisher.class)) {
            assertThatThrownBy(() -> new TracingIntegrationEventPublisher(published -> {
                throw refused;
            }).publish(event(TRACEPARENT))).isSameAs(refused);

            assertThat(logs.events("outbox.event.failed")).singleElement()
                    .satisfies(line -> assertThat(line).containsEntry("level", "WARN"));
        }
    }

    @Test
    void givesTheRelayItsOwnLogContextBackAfterEachEvent() {
        MDC.put(CurrentTrace.TRACE_ID, "relay-run");

        new TracingIntegrationEventPublisher(published -> { }).publish(event(TRACEPARENT));

        assertThat(MDC.get(CurrentTrace.TRACE_ID)).isEqualTo("relay-run");
        assertThat(MDC.get(CurrentTrace.CORRELATION_ID)).isNull();
    }

    private static OutboxEvent event(String traceparent) {
        return new OutboxEvent(UUID.randomUUID(), "PreConsultationSummaryGenerated", Instant.now(),
                UUID.randomUUID().toString(), traceparent, Map.of("patientId", PATIENT));
    }
}
