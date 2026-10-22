package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;

import java.util.UUID;

/** HU-06: the patient tells the agent something and gets its next turn. */
public interface SendMessageUseCase {

    /** Retrying with the same {@code idempotencyKey} returns the same reply without asking the model again. */
    AgentTurn send(UUID patientId, UUID conversationId, String idempotencyKey, String text);

    record AgentTurn(Message reply, Conversation conversation) {
    }
}
