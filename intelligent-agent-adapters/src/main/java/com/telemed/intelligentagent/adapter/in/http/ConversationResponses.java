package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase.PreConsultationView;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The contract's Conversation, Message and PreconsultationSummary (camelCase, UUIDs, RFC 3339 UTC). */
public final class ConversationResponses {

    private ConversationResponses() {
    }

    /** The contract's Conversation plus its summary once finished, so the portal needs one call. */
    public record ConversationResponse(UUID id, UUID patientId, UUID appointmentId, Instant startDateTime,
                                       Instant endDateTime, String status, int questionLimit, int questionsAsked,
                                       String finalizationReason, List<MessageResponse> messages,
                                       SummaryResponse preconsultationSummary) {

        public static ConversationResponse of(PreConsultationView view) {
            Conversation conversation = view.conversation();
            return new ConversationResponse(conversation.id(), conversation.patientId(),
                    conversation.appointmentId(), conversation.startedAt(), conversation.endedAt(),
                    conversation.status().name(), conversation.questionLimit(), conversation.questionsAsked(),
                    conversation.finalizationReason() == null ? null : conversation.finalizationReason().name(),
                    view.messages().stream().map(MessageResponse::of).toList(),
                    view.summary().map(SummaryResponse::of).orElse(null));
        }
    }

    // The idempotency key stays internal: it is the caller's, not part of the resource.
    public record MessageResponse(UUID id, String sender, String content, Instant sentAt) {

        public static MessageResponse of(Message message) {
            return new MessageResponse(message.id(), message.sender().name(), message.content(), message.sentAt());
        }
    }

    public record SummaryResponse(UUID id, UUID conversationId, UUID appointmentId, UUID patientId,
                                  String consultationReason, String evolutionTime, String detailedSymptoms,
                                  String relevantHistory, Instant createdAt) {

        public static SummaryResponse of(PreConsultationSummary summary) {
            return new SummaryResponse(summary.id(), summary.conversationId(), summary.appointmentId(),
                    summary.patientId(), summary.consultationReason(), summary.evolutionTime(),
                    summary.detailedSymptoms(), summary.relevantHistory(), summary.createdAt());
        }
    }
}
