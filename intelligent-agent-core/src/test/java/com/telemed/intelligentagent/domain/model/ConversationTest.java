package com.telemed.intelligentagent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_CONVERSATION;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.QUESTION_LIMIT_REACHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private static final UUID PATIENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID APPOINTMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    @Test
    void startsActiveForOnePatientAndOneAppointment() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 3, NOW);

        assertThat(conversation.id()).isNotNull();
        assertThat(conversation.patientId()).isEqualTo(PATIENT);
        assertThat(conversation.appointmentId()).isEqualTo(APPOINTMENT);
        assertThat(conversation.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(conversation.questionsAsked()).isZero();
        assertThat(conversation.startedAt()).isEqualTo(NOW);
        assertThat(conversation.endedAt()).isNull();
        assertThat(conversation.finalizationReason()).isNull();
    }

    @Test
    void cannotStartWithoutAnAppointmentOrAQuestionLimit() {
        assertThatThrownBy(() -> Conversation.start(PATIENT, null, 3, NOW))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_CONVERSATION);
        assertThatThrownBy(() -> Conversation.start(null, APPOINTMENT, 3, NOW))
                .extracting("reason").isEqualTo(INVALID_CONVERSATION);
        assertThatThrownBy(() -> Conversation.start(PATIENT, APPOINTMENT, 0, NOW))
                .extracting("reason").isEqualTo(INVALID_CONVERSATION);
    }

    @Test
    void belongsOnlyToItsPatient() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 3, NOW);

        assertThat(conversation.belongsTo(PATIENT)).isTrue();
        assertThat(conversation.belongsTo(UUID.randomUUID())).isFalse();
        assertThat(conversation.belongsTo(null)).isFalse();
    }

    @Test
    void countsTheAgentsQuestionsUpToTheLimit() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 2, NOW);

        conversation.recordAgentQuestion();
        assertThat(conversation.questionsAsked()).isEqualTo(1);
        assertThat(conversation.questionLimitReached()).isFalse();

        // The last question stays open: the patient still gets to answer it.
        conversation.recordAgentQuestion();
        assertThat(conversation.questionsAsked()).isEqualTo(2);
        assertThat(conversation.questionLimitReached()).isTrue();
        assertThat(conversation.isActive()).isTrue();

        assertThatThrownBy(conversation::recordAgentQuestion)
                .extracting("reason").isEqualTo(QUESTION_LIMIT_REACHED);
    }

    @Test
    void closesOnlyOnceTheLimitIsReached() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 1, NOW);
        assertThatThrownBy(() -> conversation.closeAtQuestionLimit(NOW))
                .extracting("reason").isEqualTo(QUESTION_LIMIT_REACHED);

        conversation.recordAgentQuestion();
        conversation.closeAtQuestionLimit(NOW.plusSeconds(20));

        assertThat(conversation.status()).isEqualTo(ConversationStatus.FINISHED);
        assertThat(conversation.finalizationReason()).isEqualTo(FinalizationReason.QUESTION_LIMIT_REACHED);
        assertThat(conversation.endedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void finishesWhenThePatientIsDone() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 3, NOW);
        conversation.recordAgentQuestion();

        conversation.finish(NOW.plusSeconds(30));

        assertThat(conversation.status()).isEqualTo(ConversationStatus.FINISHED);
        assertThat(conversation.finalizationReason()).isEqualTo(FinalizationReason.COMPLETED);
        assertThat(conversation.endedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void acceptsNothingOnceFinished() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 3, NOW);
        conversation.finish(NOW.plusSeconds(30));

        assertThatThrownBy(conversation::recordAgentQuestion)
                .extracting("reason").isEqualTo(CONVERSATION_FINISHED);
        assertThatThrownBy(() -> conversation.finish(NOW.plusSeconds(50)))
                .extracting("reason").isEqualTo(CONVERSATION_FINISHED);
        assertThatThrownBy(conversation::ensureActive)
                .extracting("reason").isEqualTo(CONVERSATION_FINISHED);
    }

    @Test
    void restoresAStoredConversationWithItsState() {
        UUID id = UUID.randomUUID();

        Conversation stored = Conversation.restore(id, PATIENT, APPOINTMENT, ConversationStatus.FINISHED, 3, 3,
                NOW, NOW.plusSeconds(60), FinalizationReason.QUESTION_LIMIT_REACHED);

        assertThat(stored.id()).isEqualTo(id);
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.questionLimit()).isEqualTo(3);
    }
}
