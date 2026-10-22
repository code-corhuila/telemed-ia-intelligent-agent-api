package com.telemed.intelligentagent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final String KEY = "6f1c2a9e-key";
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    @Test
    void recordsWhoSaidWhatAndWhen() {
        Message message = Message.of(CONVERSATION, Sender.PATIENT, KEY, "Me duele la cabeza.", NOW);

        assertThat(message.id()).isNotNull();
        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.sender()).isEqualTo(Sender.PATIENT);
        assertThat(message.idempotencyKey()).isEqualTo(KEY);
        assertThat(message.content()).isEqualTo("Me duele la cabeza.");
        assertThat(message.sentAt()).isEqualTo(NOW);
    }

    @Test
    void neverStoresEmptyContent() {
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.AGENT, KEY, "  ", NOW))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> Message.of(null, Sender.AGENT, KEY, "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void needsAnIdempotencyKeyAsAgentDbBoundsIt() {
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.PATIENT, null, "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.PATIENT, "short", "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.PATIENT, "k".repeat(129), "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.PATIENT, "key with spaces", "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
    }
}
