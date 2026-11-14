package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.IdempotencyRecords;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * agent-db's idempotency_records, unique per principal, operation and key
 * (uq_idempotency_principal_operation_key). Records expire through agent-db's
 * TTL index, so a key is remembered for {@code retention} only.
 */
public class MongoIdempotencyRecords implements IdempotencyRecords {

    static final String COLLECTION = "idempotency_records";

    private final MongoTemplate mongo;
    private final Duration retention;

    public MongoIdempotencyRecords(MongoTemplate mongo, Duration retention) {
        this.mongo = mongo;
        this.retention = retention;
    }

    @Override
    public Optional<UUID> find(UUID principalId, Operation operation, String key) {
        Query query = Query.query(Criteria.where("principalId").is(principalId.toString())
                .and("operation").is(operation.name())
                .and("key").is(key));
        return Optional.ofNullable(mongo.findOne(query, Document.class, COLLECTION))
                .map(record -> UUID.fromString(record.getString("resourceId")));
    }

    @Override
    public void save(UUID principalId, Operation operation, String key, UUID resourceId, Instant now) {
        mongo.insert(new Document("_id", UUID.randomUUID().toString())
                .append("principalId", principalId.toString())
                .append("operation", operation.name())
                .append("key", key)
                .append("resourceType", resourceType(operation))
                .append("resourceId", resourceId.toString())
                .append("createdAt", Date.from(now))
                .append("expiresAt", Date.from(now.plus(retention))), COLLECTION);
    }

    private static String resourceType(Operation operation) {
        return switch (operation) {
            case START_PRECONSULTATION -> "CONVERSATION";
        };
    }
}
