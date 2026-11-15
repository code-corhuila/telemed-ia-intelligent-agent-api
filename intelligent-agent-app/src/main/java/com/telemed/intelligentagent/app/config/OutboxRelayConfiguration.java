package com.telemed.intelligentagent.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.out.messaging.RabbitIntegrationEventPublisher;
import com.telemed.intelligentagent.adapter.out.messaging.TracingIntegrationEventPublisher;
import com.telemed.intelligentagent.adapter.out.persistence.mongo.MongoOutboxEvents;
import com.telemed.intelligentagent.application.port.in.RelayOutboxEventsUseCase;
import com.telemed.intelligentagent.application.port.out.IntegrationEventPublisherPort;
import com.telemed.intelligentagent.application.port.out.OutboxEventsPort;
import com.telemed.intelligentagent.application.service.RelayOutboxEventsService;
import io.micrometer.tracing.Tracer;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** HU-06: publishes agent-db's outbox to RabbitMQ (exchange agent.events, ADR-011). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
@ConditionalOnProperty(prefix = "agent.outbox-relay", name = "enabled", havingValue = "true")
public class OutboxRelayConfiguration {

    // Declared by its producer; consumers declare and bind their own queues.
    @Bean
    TopicExchange agentEventsExchange() {
        return new TopicExchange(RabbitIntegrationEventPublisher.EXCHANGE, true, false);
    }

    @Bean
    OutboxEventsPort outboxEvents(MongoTemplate mongo) {
        return new MongoOutboxEvents(mongo);
    }

    @Bean
    IntegrationEventPublisherPort integrationEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
                                                            OutboxRelayProperties properties,
                                                            ObjectProvider<Tracer> tracer) {
        return new TracingIntegrationEventPublisher(
                new RabbitIntegrationEventPublisher(rabbitTemplate, objectMapper, properties.confirmTimeout()),
                tracer.getIfAvailable());
    }

    @Bean
    RelayOutboxEventsUseCase relayOutboxEventsUseCase(OutboxEventsPort outbox, IntegrationEventPublisherPort publisher,
                                                      OutboxRelayProperties properties) {
        return new RelayOutboxEventsService(outbox, publisher, properties.batchSize());
    }

    @Bean
    OutboxRelayScheduler outboxRelayScheduler(RelayOutboxEventsUseCase relay) {
        return new OutboxRelayScheduler(relay);
    }
}
