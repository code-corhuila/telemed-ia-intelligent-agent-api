package com.telemed.intelligentagent.application.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One pending event of agent-db's outbox_events, as the relay publishes it (ADR-011 envelope).
 *
 * @param id            the event id, which consumers use for idempotency
 * @param eventType     PascalCase name, such as PreConsultationSummaryGenerated
 * @param occurredAt    when the business fact happened
 * @param correlationId the X-Correlation-Id of the request that produced it
 * @param traceparent   the W3C trace context of that request; null when it had none
 * @param payload       ids and dates only, already as strings; never clinical content
 */
public record OutboxEvent(
        UUID id,
        String eventType,
        Instant occurredAt,
        String correlationId,
        String traceparent,
        Map<String, String> payload
) {

    /** Envelope version: the event has had one shape since HU-06. */
    public static final int VERSION = 1;

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Map.copyOf(payload);
    }
}
