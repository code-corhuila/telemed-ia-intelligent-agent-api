package com.telemed.intelligentagent.domain.model;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;

/** One turn of the pre-consultation, said by the patient or by the agent. */
public record Message(UUID id, UUID conversationId, Sender sender, String content, Instant sentAt) {

    public Message {
        if (id == null || conversationId == null || sender == null || sentAt == null
                || content == null || content.isBlank()) {
            throw new AgentDomainException(INVALID_MESSAGE, "A message needs a conversation, a sender and content.");
        }
    }

    public static Message of(UUID conversationId, Sender sender, String content, Instant sentAt) {
        return new Message(UUID.randomUUID(), conversationId, sender, content, sentAt);
    }
}
