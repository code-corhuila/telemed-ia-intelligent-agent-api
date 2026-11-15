package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.OutboxEventsPort;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relay side of agent-db's outbox_events. It changes only {@code status}, the one field
 * agent-db's validator lets it change; idx_outbox_status_occurred_at serves the pending query.
 */
public class MongoOutboxEvents implements OutboxEventsPort {

    private static final String PENDING = "PENDING";
    private static final String PUBLISHED = "PUBLISHED";

    private final MongoTemplate mongo;

    public MongoOutboxEvents(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<OutboxEvent> pending(int limit) {
        Query query = Query.query(Criteria.where("status").is(PENDING))
                .with(Sort.by("occurredAt"))
                .limit(limit);
        return mongo.find(query, Document.class, MongoSummaryRepository.OUTBOX).stream()
                .map(MongoOutboxEvents::toEvent)
                .toList();
    }

    @Override
    public void markPublished(UUID eventId) {
        // Only from PENDING: an event someone set to FAILED by hand stays as they left it.
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(eventId.toString()).and("status").is(PENDING)),
                Update.update("status", PUBLISHED),
                MongoSummaryRepository.OUTBOX);
    }

    private static OutboxEvent toEvent(Document document) {
        Map<String, String> payload = new LinkedHashMap<>();
        document.get("payload", Document.class).forEach((field, value) ->
                payload.put(field, value instanceof Date date ? date.toInstant().toString() : String.valueOf(value)));
        return new OutboxEvent(
                UUID.fromString(document.getString("_id")),
                document.getString("eventType"),
                document.getDate("occurredAt").toInstant(),
                document.getString("correlationId"),
                document.getString("traceparent"),
                payload);
    }
}
