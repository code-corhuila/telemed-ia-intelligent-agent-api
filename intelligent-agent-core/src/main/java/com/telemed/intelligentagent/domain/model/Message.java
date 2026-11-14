package com.telemed.intelligentagent.domain.model;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;

/**
 * One turn of the pre-consultation, said by the patient or by the agent.
 * The idempotency key is unique per conversation and sender (agent-db's
 * uq_messages_conversation_sender_idempotency), so a retried turn is stored once.
 */
public record Message(UUID id, UUID conversationId, Sender sender, String idempotencyKey, String content,
                      Instant sentAt) {

    public Message {
        if (id == null || conversationId == null || sender == null || sentAt == null
                || content == null || content.isBlank()) {
            throw new AgentDomainException(INVALID_MESSAGE, "A message needs a conversation, a sender and content.");
        }
        if (!IdempotencyKeys.isValid(idempotencyKey)) {
            throw new AgentDomainException(INVALID_MESSAGE,
                    "A message needs an idempotency key of 8 to 128 printable characters.");
        }
    }

    public static Message of(UUID conversationId, Sender sender, String idempotencyKey, String content,
                             Instant sentAt) {
        return new Message(UUID.randomUUID(), conversationId, sender, idempotencyKey, content, sentAt);
    }
}
