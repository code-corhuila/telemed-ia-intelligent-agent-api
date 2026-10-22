package com.telemed.intelligentagent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    @Test
    void recordsWhoSaidWhatAndWhen() {
        Message message = Message.of(CONVERSATION, Sender.PATIENT, "Me duele la cabeza.", NOW);

        assertThat(message.id()).isNotNull();
        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.sender()).isEqualTo(Sender.PATIENT);
        assertThat(message.content()).isEqualTo("Me duele la cabeza.");
        assertThat(message.sentAt()).isEqualTo(NOW);
    }

    @Test
    void neverStoresEmptyContent() {
        assertThatThrownBy(() -> Message.of(CONVERSATION, Sender.AGENT, "  ", NOW))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> Message.of(null, Sender.AGENT, "Hola", NOW))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
    }
}
