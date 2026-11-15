package com.telemed.intelligentagent.adapter.out.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HU-06: the agent publishes PreConsultationSummaryGenerated to agent.events and waits for the broker. */
class RabbitIntegrationEventPublisherTest {

    private static final String QUEUE = "test.pre-consultation-summary-generated";
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.12-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CachingConnectionFactory connections;
    private static RabbitTemplate rabbit;
    private static RabbitAdmin admin;

    @BeforeAll
    static void startBroker() {
        RABBITMQ.start();
        connections = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getAmqpPort());
        connections.setUsername(RABBITMQ.getAdminUsername());
        connections.setPassword(RABBITMQ.getAdminPassword());
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        rabbit = new RabbitTemplate(connections);
        rabbit.setMandatory(true);
        admin = new RabbitAdmin(connections);
        admin.declareExchange(new TopicExchange(RabbitIntegrationEventPublisher.EXCHANGE));
    }

    @AfterAll
    static void stopBroker() {
        connections.destroy();
        RABBITMQ.stop();
    }

    @AfterEach
    void cleanUp() {
        admin.deleteQueue(QUEUE);
        MDC.clear();
    }

    @Test
    void publishesTheAdr011EnvelopeWithTheEventIdentity() throws Exception {
        bindQueue();
        OutboxEvent event = event();

        publisher().publish(event);

        Message message = rabbit.receive(QUEUE, 5_000);
        assertThat(message).isNotNull();
        JsonNode body = JSON.readTree(message.getBody());
        assertThat(body.get("eventId").asText()).isEqualTo(event.id().toString());
        assertThat(body.get("eventType").asText()).isEqualTo("PreConsultationSummaryGenerated");
        assertThat(body.get("occurredAt").asText()).isEqualTo("2026-11-06T15:00:00.123Z");
        assertThat(body.get("correlationId").asText()).isEqualTo(event.correlationId());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("payload").get("summaryId").asText()).isEqualTo(event.payload().get("summaryId"));
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.id().toString());
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(message.getMessageProperties().getType()).isEqualTo("PreConsultationSummaryGenerated");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void sendsTheCurrentTraceContextSoTheConsumerContinuesTheTrace() {
        bindQueue();
        MDC.put(CurrentTrace.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put(CurrentTrace.SPAN_ID, "00f067aa0ba902b7");

        publisher().publish(event());

        Message message = rabbit.receive(QUEUE, 5_000);
        assertThat(message.getMessageProperties().<String>getHeader("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void failsWhenNoQueueTakesTheEvent() {
        // mandatory: the broker returns it, so the event stays pending until a consumer binds its queue.
        assertThatThrownBy(() -> publisher().publish(event()))
                .isInstanceOf(EventPublicationException.class)
                .hasMessageContaining("PreConsultationSummaryGenerated");
    }

    @Test
    void routesByTheEventNameInKebabCase() {
        assertThat(RabbitIntegrationEventPublisher.routingKey("PreConsultationSummaryGenerated"))
                .isEqualTo("pre-consultation-summary-generated");
    }

    private static void bindQueue() {
        Queue queue = new Queue(QUEUE, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(RabbitIntegrationEventPublisher.EXCHANGE))
                .with("pre-consultation-summary-generated"));
    }

    private static RabbitIntegrationEventPublisher publisher() {
        return new RabbitIntegrationEventPublisher(rabbit, JSON, Duration.ofSeconds(5));
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "PreConsultationSummaryGenerated",
                Instant.parse("2026-11-06T15:00:00.123Z"), UUID.randomUUID().toString(), null,
                Map.of("summaryId", UUID.randomUUID().toString(), "generatedAt", "2026-11-06T15:00:00.123Z"));
    }
}
