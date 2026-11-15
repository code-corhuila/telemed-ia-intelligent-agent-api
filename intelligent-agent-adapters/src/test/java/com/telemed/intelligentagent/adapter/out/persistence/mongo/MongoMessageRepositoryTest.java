package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MongoMessageRepositoryTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");

    private MongoTemplate mongo;
    private MongoMessageRepository repository;

    @BeforeEach
    void freshDatabase() {
        mongo = AgentDbTestDatabase.freshTemplate();
        repository = new MongoMessageRepository(mongo);
    }

    @Test
    void returnsAConversationsMessagesOldestFirst() {
        Message answer = Message.of(CONVERSATION, Sender.AGENT, "turn-key-0001", "¿Desde cuándo?", NOW.plusMillis(1));
        Message question = Message.of(CONVERSATION, Sender.PATIENT, "turn-key-0001", "Me duele la cabeza", NOW);
        Message opening = Message.of(CONVERSATION, Sender.AGENT, "opening-key-01", "¿Qué te trae?",
                NOW.minusSeconds(30));
        repository.save(answer);
        repository.save(question);
        repository.save(opening);
        repository.save(Message.of(UUID.randomUUID(), Sender.AGENT, "opening-key-01", "Otra", NOW));

        assertThat(repository.findByConversation(CONVERSATION)).containsExactly(opening, question, answer);
    }

    @Test
    void writesOnlyTheFieldsAgentDbAccepts() {
        Message message = Message.of(CONVERSATION, Sender.PATIENT, "turn-key-0001", "Me duele la cabeza", NOW);
        repository.save(message);

        Document stored = mongo.getCollection("messages").find().first();

        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "conversationId", "sender", "idempotencyKey",
                "content", "timestamp");
        assertThat(stored.get("_id")).isEqualTo(message.id().toString());
        assertThat(stored.get("sender")).isEqualTo("PATIENT");
        assertThat(stored.get("timestamp")).isEqualTo(Date.from(NOW));
    }
}
