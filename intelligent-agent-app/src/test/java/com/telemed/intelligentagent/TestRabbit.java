package com.telemed.intelligentagent;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.RabbitMQContainer;

/** The platform broker for the app tests, the version telemed-ia-worker runs. */
final class TestRabbit {

    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.12-alpine");

    private TestRabbit() {
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        if (!RABBITMQ.isRunning()) {
            RABBITMQ.start();
        }
        registry.add("RABBITMQ_HOST", RABBITMQ::getHost);
        registry.add("RABBITMQ_PORT", RABBITMQ::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBITMQ::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBITMQ::getAdminPassword);
    }
}
