package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/** agent-db's messages, written with exactly the fields its validator declares. */
public class MongoMessageRepository implements MessageRepository {

    static final String COLLECTION = "messages";

    private final MongoTemplate mongo;

    public MongoMessageRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Message> findByConversation(UUID conversationId) {
        // Served by idx_messages_conversation_timestamp.
        Query query = Query.query(Criteria.where("conversationId").is(conversationId.toString()))
                .with(Sort.by("timestamp"));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(MongoMessageRepository::toMessage)
                .toList();
    }

    @Override
    public void save(Message message) {
        mongo.insert(new Document("_id", message.id().toString())
                .append("conversationId", message.conversationId().toString())
                .append("sender", message.sender().name())
                .append("idempotencyKey", message.idempotencyKey())
                .append("content", message.content())
                .append("timestamp", Date.from(message.sentAt())), COLLECTION);
    }

    private static Message toMessage(Document document) {
        return new Message(
                UUID.fromString(document.getString("_id")),
                UUID.fromString(document.getString("conversationId")),
                Sender.valueOf(document.getString("sender")),
                document.getString("idempotencyKey"),
                document.getString("content"),
                document.getDate("timestamp").toInstant());
    }
}
