package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;

/**
 * A single-node replica set (as infra-mongo runs) with agent-db's unique
 * indexes (changesets hu-06-004 and hu-06-009), which the adapters rely on.
 */
final class AgentDbTestDatabase {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.32");

    private static MongoClient client;

    private AgentDbTestDatabase() {
    }

    static synchronized MongoTemplate freshTemplate() {
        if (!MONGO.isRunning()) {
            MONGO.start();
            client = MongoClients.create(MONGO.getReplicaSetUrl());
        }
        MongoTemplate template = new MongoTemplate(client, "intelligent_agent");
        template.getDb().drop();
        template.getDb().runCommand(Document.parse("""
                {"createIndexes":"conversations","indexes":[
                  {"key":{"patientId":1},"name":"uq_conversations_active_patient","unique":true,
                   "partialFilterExpression":{"status":"ACTIVE"}},
                  {"key":{"appointmentId":1},"name":"uq_conversations_appointment","unique":true,
                   "partialFilterExpression":{"appointmentId":{"$type":"string"}}}]}"""));
        template.getDb().runCommand(Document.parse("""
                {"createIndexes":"messages","indexes":[
                  {"key":{"conversationId":1,"timestamp":1},"name":"idx_messages_conversation_timestamp"},
                  {"key":{"conversationId":1,"sender":1,"idempotencyKey":1},
                   "name":"uq_messages_conversation_sender_idempotency","unique":true}]}"""));
        return template;
    }
}
