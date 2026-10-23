package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;

import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;

/** Read-only; another patient's pre-consultation is reported exactly like a missing one. */
public class GetPreConsultationService implements GetPreConsultationUseCase {

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public GetPreConsultationService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Override
    public PreConsultationView get(UUID patientId, UUID conversationId) {
        return viewOf(patientId, conversations.findById(conversationId));
    }

    @Override
    public PreConsultationView getByAppointment(UUID patientId, UUID appointmentId) {
        return viewOf(patientId, conversations.findByAppointment(appointmentId));
    }

    private PreConsultationView viewOf(UUID patientId, Optional<Conversation> found) {
        Conversation conversation = found
                .filter(candidate -> candidate.belongsTo(patientId))
                .orElseThrow(() -> new AgentDomainException(CONVERSATION_NOT_FOUND, "The conversation was not found."));
        return new PreConsultationView(conversation, messages.findByConversation(conversation.id()));
    }
}
