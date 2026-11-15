package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.event.OutboxEvent;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MongoOutboxEventsTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private MongoTemplate mongo;
    private MongoOutboxEvents outbox;

    @BeforeEach
    void freshDatabase() {
        mongo = AgentDbTestDatabase.freshTemplate();
        outbox = new MongoOutboxEvents(mongo);
    }

    @Test
    void readsAPendingEventWithItsEnvelopeFieldsAndPayload() {
        UUID id = UUID.randomUUID();
        UUID summary = UUID.randomUUID();
        insert(id, summary, "PENDING", Instant.parse("2026-11-06T15:00:00.123Z"), TRACEPARENT);

        OutboxEvent event = outbox.pending(10).get(0);

        assertThat(event.id()).isEqualTo(id);
        assertThat(event.eventType()).isEqualTo("PreConsultationSummaryGenerated");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-06T15:00:00.123Z"));
        assertThat(event.traceparent()).isEqualTo(TRACEPARENT);
        assertThat(event.payload())
                .containsEntry("summaryId", summary.toString())
                .containsEntry("generatedAt", "2026-11-06T15:00:00.123Z")
                .containsKeys("appointmentId", "patientId", "conversationId");
    }

    @Test
    void listsOnlyPendingEventsOldestFirstUpToTheLimit() {
        UUID newest = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        insert(newest, UUID.randomUUID(), "PENDING", Instant.parse("2026-11-06T15:03:00Z"), null);
        insert(oldest, UUID.randomUUID(), "PENDING", Instant.parse("2026-11-06T15:01:00Z"), null);
        insert(middle, UUID.randomUUID(), "PENDING", Instant.parse("2026-11-06T15:02:00Z"), null);
        insert(UUID.randomUUID(), UUID.randomUUID(), "PUBLISHED", Instant.parse("2026-11-06T15:00:00Z"), null);

        List<UUID> ids = outbox.pending(2).stream().map(OutboxEvent::id).toList();

        assertThat(ids).containsExactly(oldest, middle);
    }

    @Test
    void readsAnEventWithoutTraceContextAsNull() {
        insert(UUID.randomUUID(), UUID.randomUUID(), "PENDING", Instant.now(), null);

        assertThat(outbox.pending(1).get(0).traceparent()).isNull();
    }

    @Test
    void marksTheEventPublished() {
        UUID id = UUID.randomUUID();
        insert(id, UUID.randomUUID(), "PENDING", Instant.now(), null);

        outbox.markPublished(id);

        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void leavesAFailedEventAsItIsWhenMarkingIt() {
        UUID id = UUID.randomUUID();
        insert(id, UUID.randomUUID(), "FAILED", Instant.now(), null);

        outbox.markPublished(id);

        assertThat(status(id)).isEqualTo("FAILED");
    }

    // The shape MongoSummaryRepository writes (agent-db validator, changeset hu-06-010).
    private void insert(UUID id, UUID summary, String status, Instant occurredAt, String traceparent) {
        Document event = new Document("_id", id.toString())
                .append("eventType", "PreConsultationSummaryGenerated")
                .append("aggregateType", "PRECONSULTATION_SUMMARY")
                .append("aggregateId", summary.toString())
                .append("correlationId", UUID.randomUUID().toString())
                .append("status", status)
                .append("occurredAt", Date.from(occurredAt))
                .append("createdAt", Date.from(occurredAt))
                .append("payload", new Document("summaryId", summary.toString())
                        .append("appointmentId", UUID.randomUUID().toString())
                        .append("patientId", UUID.randomUUID().toString())
                        .append("conversationId", UUID.randomUUID().toString())
                        .append("generatedAt", Date.from(occurredAt)));
        if (traceparent != null) {
            event.append("traceparent", traceparent);
        }
        mongo.insert(event, "outbox_events");
    }

    private String status(UUID id) {
        return mongo.getCollection("outbox_events").find(new Document("_id", id.toString())).first()
                .getString("status");
    }
}
