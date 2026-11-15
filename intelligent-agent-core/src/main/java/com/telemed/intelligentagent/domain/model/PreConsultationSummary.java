package com.telemed.intelligentagent.domain.model;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_SUMMARY;

/**
 * What the professional reads before the appointment: only what the patient
 * reported. Same shape as agent-db's preconsultation_summaries, where the
 * optional fields are either absent or non-empty.
 */
public record PreConsultationSummary(UUID id, UUID conversationId, UUID appointmentId, UUID patientId,
                                     String consultationReason, String evolutionTime, String detailedSymptoms,
                                     String relevantHistory, Instant createdAt) {

    public PreConsultationSummary {
        consultationReason = present(consultationReason);
        evolutionTime = present(evolutionTime);
        detailedSymptoms = present(detailedSymptoms);
        relevantHistory = present(relevantHistory);
        if (id == null || conversationId == null || appointmentId == null || patientId == null
                || createdAt == null || consultationReason == null) {
            throw new AgentDomainException(INVALID_SUMMARY,
                    "A summary needs its conversation, appointment, patient and a consultation reason.");
        }
    }

    public static PreConsultationSummary of(Conversation conversation, String consultationReason,
                                            String evolutionTime, String detailedSymptoms,
                                            String relevantHistory, Instant now) {
        return new PreConsultationSummary(UUID.randomUUID(), conversation.id(), conversation.appointmentId(),
                conversation.patientId(), consultationReason, evolutionTime, detailedSymptoms, relevantHistory,
                now);
    }

    private static String present(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
