package com.telemed.intelligentagent;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MongoDBContainer;

/** agent-db for the app tests: one replica set (as infra-mongo runs) shared by every context. */
final class TestMongo {

    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.32");

    private TestMongo() {
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
        registry.add("MONGODB_URI", () -> MONGO.getReplicaSetUrl("intelligent_agent"));
    }
}
