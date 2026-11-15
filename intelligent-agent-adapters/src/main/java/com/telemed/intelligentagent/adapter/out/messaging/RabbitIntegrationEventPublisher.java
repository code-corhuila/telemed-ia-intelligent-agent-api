package com.telemed.intelligentagent.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telemed.intelligentagent.adapter.in.http.observability.TraceContext;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import com.telemed.intelligentagent.application.port.out.IntegrationEventPublisherPort;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes to the topic exchange agent.events with the routing key = event name in kebab case
 * (PreConsultationSummaryGenerated -> pre-consultation-summary-generated) and the ADR-011 envelope
 * as body (communication-patterns.md).
 *
 * <p>Needs a template with correlated publisher confirms, publisher returns and mandatory on: an
 * event counts as published only when a queue took it.
 */
public class RabbitIntegrationEventPublisher implements IntegrationEventPublisherPort {

    public static final String EXCHANGE = "agent.events";

    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final Duration confirmTimeout;

    public RabbitIntegrationEventPublisher(RabbitTemplate rabbit, ObjectMapper json, Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.json = json;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(OutboxEvent event) {
        CorrelationData confirmation = new CorrelationData(event.id().toString());
        try {
            rabbit.send(EXCHANGE, routingKey(event.eventType()), message(event), confirmation);
            CorrelationData.Confirm confirm =
                    confirmation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || confirmation.getReturned() != null) {
                throw failure(event, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(event, interrupted);
        } catch (AmqpException | ExecutionException | TimeoutException brokerFailure) {
            throw failure(event, brokerFailure);
        }
    }

    static String routingKey(String eventType) {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private Message message(OutboxEvent event) {
        ObjectNode envelope = json.createObjectNode()
                .put("eventId", event.id().toString())
                .put("eventType", event.eventType())
                .put("occurredAt", event.occurredAt().toString())
                .put("correlationId", event.correlationId())
                .put("version", OutboxEvent.VERSION);
        envelope.set("payload", json.valueToTree(event.payload()));
        try {
            MessageBuilderSupport<Message> message = MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setMessageId(event.id().toString())
                    .setCorrelationId(event.correlationId())
                    .setType(event.eventType())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            // The consumer continues the trace this event is published in (week 11, CP3).
            String traceparent = CurrentTrace.traceparent();
            if (traceparent != null) {
                message.setHeader(TraceContext.HEADER, traceparent);
            }
            return message.build();
        } catch (JsonProcessingException serialization) {
            throw failure(event, null);
        }
    }

    // The event identity only, never the payload.
    private static EventPublicationException failure(OutboxEvent event, Throwable cause) {
        return new EventPublicationException(
                "Broker did not take " + event.eventType() + " " + event.id() + ".", cause);
    }
}
