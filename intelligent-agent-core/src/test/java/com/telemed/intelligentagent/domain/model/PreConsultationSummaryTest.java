package com.telemed.intelligentagent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_SUMMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreConsultationSummaryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private final Conversation conversation = Conversation.start(UUID.randomUUID(), UUID.randomUUID(), 3, NOW);

    @Test
    void belongsToTheConversationsAppointmentAndPatient() {
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, "Dolor de cabeza",
                "Dos días", "Fotofobia", null, NOW);

        assertThat(summary.id()).isNotNull();
        assertThat(summary.conversationId()).isEqualTo(conversation.id());
        assertThat(summary.appointmentId()).isEqualTo(conversation.appointmentId());
        assertThat(summary.patientId()).isEqualTo(conversation.patientId());
        assertThat(summary.createdAt()).isEqualTo(NOW);
    }

    @Test
    void storesBlankFieldsAsMissingAsAgentDbExpects() {
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, " Tos ", "  ", "", null, NOW);

        assertThat(summary.consultationReason()).isEqualTo("Tos");
        assertThat(summary.evolutionTime()).isNull();
        assertThat(summary.detailedSymptoms()).isNull();
        assertThat(summary.relevantHistory()).isNull();
    }

    @Test
    void alwaysHasAConsultationReason() {
        assertThatThrownBy(() -> PreConsultationSummary.of(conversation, " ", null, null, null, NOW))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_SUMMARY);
    }
}
