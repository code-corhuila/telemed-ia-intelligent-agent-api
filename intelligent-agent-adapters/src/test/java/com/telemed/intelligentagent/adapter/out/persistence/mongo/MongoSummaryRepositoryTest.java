package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.adapter.CapturedLogs;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoSummaryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");
    private static final UUID CORRELATION = UUID.randomUUID();

    private final Conversation conversation = Conversation.start(UUID.randomUUID(), UUID.randomUUID(), 8, NOW);
    private MongoTemplate mongo;
    private MongoSummaryRepository repository;
    // Outside a traced request there is none.
    private String traceparent;

    @BeforeEach
    void freshDatabase() {
        mongo = AgentDbTestDatabase.freshTemplate();
        repository = new MongoSummaryRepository(mongo,
                new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory())),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), () -> CORRELATION, () -> traceparent);
    }

    @Test
    void recordsTheTraceContextOfTheRequestWithTheEvent() {
        // Week 11 (CP1): the relay copies it into the broker headers, so the consumer continues the trace.
        traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        repository.save(summary("Dolor de cabeza", null));

        Document event = mongo.getCollection("outbox_events").find().first();
        assertThat(event.getString("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void storesTheSummaryWithOnlyTheFieldsItHas() {
        PreConsultationSummary summary = summary("Dolor de cabeza", "Dos días");
        repository.save(summary);

        Document stored = mongo.getCollection("preconsultation_summaries").find().first();

        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "conversationId", "appointmentId", "patientId",
                "consultationReason", "evolutionTime", "createdAt");
        assertThat(stored.get("createdAt")).isEqualTo(Date.from(NOW));
        assertThat(repository.findByConversation(conversation.id())).contains(summary);
        assertThat(repository.findByConversation(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findsTheSummaryOfAnAppointment() {
        PreConsultationSummary summary = summary("Dolor de cabeza", null);
        repository.save(summary);

        assertThat(repository.findByAppointment(conversation.appointmentId())).contains(summary);
        assertThat(repository.findByAppointment(UUID.randomUUID())).isEmpty();
    }

    @Test
    void recordsThePreConsultationSummaryGeneratedEvent() {
        PreConsultationSummary summary = summary("Dolor de cabeza", null);
        repository.save(summary);

        Document event = mongo.getCollection("outbox_events").find().first();

        assertThat(event.keySet()).containsExactlyInAnyOrder("_id", "eventType", "aggregateType", "aggregateId",
                "correlationId", "status", "occurredAt", "createdAt", "payload");
        assertThat(event.get("eventType")).isEqualTo("PreConsultationSummaryGenerated");
        assertThat(event.get("aggregateType")).isEqualTo("PRECONSULTATION_SUMMARY");
        assertThat(event.get("aggregateId")).isEqualTo(summary.id().toString());
        assertThat(event.get("correlationId")).isEqualTo(CORRELATION.toString());
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("occurredAt")).isEqualTo(Date.from(NOW));
        assertThat(event.get("createdAt")).isEqualTo(Date.from(NOW.plusSeconds(1)));
        // Ids only: the clinical content never leaves agent-db through the event.
        assertThat(event.get("payload", Document.class)).isEqualTo(new Document()
                .append("summaryId", summary.id().toString())
                .append("appointmentId", conversation.appointmentId().toString())
                .append("patientId", conversation.patientId().toString())
                .append("conversationId", conversation.id().toString())
                .append("generatedAt", Date.from(NOW)));
    }

    @Test
    void logsTheGeneratedSummaryWithItsOutboxEvent() {
        PreConsultationSummary summary = summary("Dolor de cabeza", null);

        try (CapturedLogs logs = CapturedLogs.of(MongoSummaryRepository.class)) {
            repository.save(summary);

            Document event = mongo.getCollection("outbox_events").find().first();
            assertThat(logs.events("preconsultation.summary.generated")).singleElement().satisfies(line -> assertThat(line)
                    .containsEntry("level", "INFO")
                    .containsEntry("summary_id", summary.id().toString())
                    .containsEntry("conversation_id", conversation.id().toString())
                    .containsEntry("outbox_event_id", event.getString("_id")));
            assertThat(logs.everything()).doesNotContain("Dolor de cabeza");
        }
    }

    @Test
    void storesTheSummaryOnlyTogetherWithItsEvent() {
        PreConsultationSummary summary = summary("Dolor de cabeza", null);
        mongo.insert(new Document("_id", UUID.randomUUID().toString())
                .append("aggregateId", summary.id().toString())
                .append("eventType", "PreConsultationSummaryGenerated"), "outbox_events");

        assertThatThrownBy(() -> repository.save(summary)).isInstanceOf(RuntimeException.class);
        assertThat(mongo.getCollection("preconsultation_summaries").countDocuments()).isZero();
    }

    @Test
    void keepsASingleSummaryPerConversation() {
        repository.save(summary("Dolor de cabeza", null));

        assertThatThrownBy(() -> repository.save(summary("Otra cosa", null)))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(CONVERSATION_FINISHED);
        assertThat(mongo.getCollection("preconsultation_summaries").countDocuments()).isEqualTo(1);
        assertThat(mongo.getCollection("outbox_events").countDocuments()).isEqualTo(1);
    }

    private PreConsultationSummary summary(String reason, String evolutionTime) {
        return PreConsultationSummary.of(conversation, reason, evolutionTime, null, null, NOW);
    }
}
