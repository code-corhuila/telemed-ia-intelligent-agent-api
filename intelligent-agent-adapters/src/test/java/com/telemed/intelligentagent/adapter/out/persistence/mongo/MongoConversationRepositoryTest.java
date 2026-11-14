package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.ConversationStatus;
import com.telemed.intelligentagent.domain.model.FinalizationReason;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.PRECONSULTATION_ALREADY_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoConversationRepositoryTest {

    private static final UUID PATIENT = UUID.randomUUID();
    // Mongo dates keep milliseconds.
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");

    private MongoTemplate mongo;
    private MongoConversationRepository repository;

    @BeforeEach
    void freshDatabase() {
        mongo = AgentDbTestDatabase.freshTemplate();
        repository = new MongoConversationRepository(mongo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesAConversationAndRestoresItsWholeState() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 2, NOW);
        repository.save(conversation);
        conversation.recordAgentQuestion();
        conversation.finish(NOW.plusSeconds(30));
        repository.save(conversation);

        Conversation restored = repository.findById(conversation.id()).orElseThrow();

        assertThat(restored.patientId()).isEqualTo(PATIENT);
        assertThat(restored.appointmentId()).isEqualTo(conversation.appointmentId());
        assertThat(restored.status()).isEqualTo(ConversationStatus.FINISHED);
        assertThat(restored.questionLimit()).isEqualTo(2);
        assertThat(restored.questionsAsked()).isEqualTo(1);
        assertThat(restored.startedAt()).isEqualTo(NOW);
        assertThat(restored.endedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(restored.finalizationReason()).isEqualTo(FinalizationReason.COMPLETED);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void writesOnlyTheFieldsAgentDbAccepts() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW);
        repository.save(conversation);

        Document stored = mongo.getCollection("conversations").find().first();

        // agent-db's validator has additionalProperties: false, so no _class or extra field may appear.
        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "patientId", "appointmentId",
                "startDateTime", "status", "questionLimit", "questionsAsked", "createdAt", "updatedAt");
        assertThat(stored.get("_id")).isEqualTo(conversation.id().toString());
        assertThat(stored.get("status")).isEqualTo("ACTIVE");
        assertThat(stored.get("questionLimit")).isInstanceOf(Integer.class);
        assertThat(stored.get("startDateTime")).isEqualTo(Date.from(NOW));
    }

    @Test
    void keepsTheCreationTimeWhenUpdating() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW);
        repository.save(conversation);
        Instant later = NOW.plus(5, ChronoUnit.MINUTES);
        conversation.finish(later);
        new MongoConversationRepository(mongo, Clock.fixed(later, ZoneOffset.UTC)).save(conversation);

        Document stored = mongo.getCollection("conversations").find().first();

        assertThat(stored.get("createdAt")).isEqualTo(Date.from(NOW));
        assertThat(stored.get("updatedAt")).isEqualTo(Date.from(later));
        assertThat(stored.get("endDateTime")).isEqualTo(Date.from(later));
        assertThat(stored.get("finalizationReason")).isEqualTo("COMPLETED");
    }

    @Test
    void findsByAppointmentAndActivePatient() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW);
        repository.save(conversation);

        assertThat(repository.findByAppointment(conversation.appointmentId())).isPresent();
        assertThat(repository.existsForAppointment(conversation.appointmentId())).isTrue();
        assertThat(repository.existsForAppointment(UUID.randomUUID())).isFalse();
        assertThat(repository.hasActiveFor(PATIENT)).isTrue();

        conversation.finish(NOW.plusSeconds(60));
        repository.save(conversation);
        assertThat(repository.hasActiveFor(PATIENT)).isFalse();
    }

    @Test
    void reportsAgentDbUniquenessAsTheDomainRules() {
        UUID appointment = UUID.randomUUID();
        Conversation first = Conversation.start(PATIENT, appointment, 8, NOW);
        repository.save(first);
        first.finish(NOW.plusSeconds(60));
        repository.save(first);

        // Two requests racing past the service checks still end in the same rules.
        assertThatThrownBy(() -> repository.save(Conversation.start(UUID.randomUUID(), appointment, 8, NOW)))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(PRECONSULTATION_ALREADY_EXISTS);

        repository.save(Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW));
        assertThatThrownBy(() -> repository.save(Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW)))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(ACTIVE_PRECONSULTATION_EXISTS);
    }
}
