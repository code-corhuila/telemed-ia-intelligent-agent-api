package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.IdempotencyRecords.Operation;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MongoIdempotencyRecordsTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");

    private MongoTemplate mongo;
    private MongoIdempotencyRecords records;

    @BeforeEach
    void freshDatabase() {
        mongo = AgentDbTestDatabase.freshTemplate();
        records = new MongoIdempotencyRecords(mongo, Duration.ofHours(24));
    }

    @Test
    void remembersWhichResourceAKeyCreatedForItsPatient() {
        records.save(PATIENT, Operation.START_PRECONSULTATION, "start-key-0001", CONVERSATION, NOW);

        assertThat(records.find(PATIENT, Operation.START_PRECONSULTATION, "start-key-0001")).contains(CONVERSATION);
        assertThat(records.find(UUID.randomUUID(), Operation.START_PRECONSULTATION, "start-key-0001")).isEmpty();
        assertThat(records.find(PATIENT, Operation.START_PRECONSULTATION, "start-key-0002")).isEmpty();
    }

    @Test
    void writesOnlyTheFieldsAgentDbAcceptsAndExpiresThem() {
        records.save(PATIENT, Operation.START_PRECONSULTATION, "start-key-0001", CONVERSATION, NOW);

        Document stored = mongo.getCollection("idempotency_records").find().first();

        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "principalId", "operation", "key",
                "resourceType", "resourceId", "createdAt", "expiresAt");
        assertThat(stored.get("operation")).isEqualTo("START_PRECONSULTATION");
        assertThat(stored.get("resourceType")).isEqualTo("CONVERSATION");
        assertThat(stored.get("resourceId")).isEqualTo(CONVERSATION.toString());
        // agent-db's TTL index (idx_idempotency_expires_at) removes the record once it expires.
        assertThat(stored.get("expiresAt")).isEqualTo(Date.from(NOW.plus(Duration.ofHours(24))));
    }
}
