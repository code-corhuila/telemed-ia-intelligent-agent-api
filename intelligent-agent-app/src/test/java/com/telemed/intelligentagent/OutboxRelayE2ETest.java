package com.telemed.intelligentagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HU-06 end to end: a finished pre-consultation's PreConsultationSummaryGenerated leaves agent-db's
 * outbox for agent.events, in the trace of the request that produced it (week 11, CP1/CP3).
 */
@AutoConfigureObservability
@SpringBootTest(properties = {
        "management.server.port=0",
        "management.otlp.tracing.export.enabled=false",
        "agent.outbox-relay.enabled=true",
        "agent.outbox-relay.interval=PT0.2S"
})
class OutboxRelayE2ETest {

    private static final String QUEUE = "test.agent.pre-consultation-summary-generated";
    // One trace per test: an event a previous test left pending can still reach this test's queue.
    private final String traceId = UUID.randomUUID().toString().replace("-", "");
    private static final String REQUEST_SPAN = "00f067aa0ba902b7";

    @Autowired
    private SummaryRepository summaries;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin admin;
    @Autowired
    private InMemorySpanExporter spans;
    @Autowired
    private SdkTracerProvider tracerProvider;

    @TestConfiguration
    static class CollectedSpans {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
        TestRabbit.register(registry);
    }

    @BeforeEach
    void forgetEarlierSpans() {
        spans.reset();
    }

    @AfterEach
    void cleanUp() {
        admin.deleteQueue(QUEUE);
        // An event left pending here would reach the next test's queue.
        mongo.getCollection("outbox_events").deleteMany(new Document());
        MDC.clear();
    }

    @Test
    void publishesTheSummaryEventToAgentEventsAndMarksItPublished() throws Exception {
        // As the appointment-service consumer would: its own queue, bound by routing key.
        Queue queue = new Queue(QUEUE, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange("agent.events")).with("pre-consultation-summary-generated"));

        PreConsultationSummary summary = finishedInARequest();

        Message message = messageOfThisTest();
        assertThat(message).isNotNull();
        JsonNode body = new ObjectMapper().readTree(message.getBody());
        assertThat(body.get("eventType").asText()).isEqualTo("PreConsultationSummaryGenerated");
        assertThat(body.get("payload").get("summaryId").asText()).isEqualTo(summary.id().toString());
        assertThat(body.get("payload").get("appointmentId").asText()).isEqualTo(summary.appointmentId().toString());
        assertThat(message.getMessageProperties().<String>getHeader("traceparent")).contains(traceId);
        assertThat(outboxStatus(summary)).isEqualTo("PUBLISHED");
    }

    @Test
    void thePublicationIsAProducerSpanUnderTheRequestThatFinishedThePreConsultation() throws Exception {
        Queue queue = new Queue(QUEUE, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange("agent.events")).with("pre-consultation-summary-generated"));

        finishedInARequest();

        SpanData publish = exportedPublishSpan().orElseThrow();
        assertThat(publish.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(publish.getParentSpanId()).isEqualTo(REQUEST_SPAN);
        Message message = messageOfThisTest();
        assertThat(message.getMessageProperties().<String>getHeader("traceparent"))
                .isEqualTo("00-" + traceId + "-" + publish.getSpanId() + "-01");
    }

    @Test
    void keepsTheEventPendingWhileNoConsumerHasBoundItsQueue() throws Exception {
        PreConsultationSummary summary = finishedInARequest();

        Thread.sleep(1_000);

        assertThat(outboxStatus(summary)).isEqualTo("PENDING");
    }

    /** What /finish leaves behind: the summary and its outbox event, with the request's trace context. */
    private PreConsultationSummary finishedInARequest() {
        MDC.put(CurrentTrace.TRACE_ID, traceId);
        MDC.put(CurrentTrace.SPAN_ID, REQUEST_SPAN);
        PreConsultationSummary summary = new PreConsultationSummary(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Dolor de cabeza", null, null, null, Instant.now());
        summaries.save(summary);
        MDC.clear();
        return summary;
    }

    private String outboxStatus(PreConsultationSummary summary) throws InterruptedException {
        String status = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            Document event = mongo.getCollection("outbox_events")
                    .find(new Document("aggregateId", summary.id().toString())).first();
            status = event.getString("status");
            if ("PUBLISHED".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }

    /** The message published in this test's trace; any other one is skipped. */
    private Message messageOfThisTest() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbit.receive(QUEUE, 1_000);
            String traceparent = message == null ? null : message.getMessageProperties().getHeader("traceparent");
            if (traceparent != null && traceparent.contains(traceId)) {
                return message;
            }
        }
        return null;
    }

    private Optional<SpanData> exportedPublishSpan() throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            tracerProvider.forceFlush().join(1, TimeUnit.SECONDS);
            Optional<SpanData> span = spans.getFinishedSpanItems().stream()
                    .filter(item -> item.getTraceId().equals(traceId))
                    .filter(item -> item.getName().equals("agent.events publish"))
                    .findFirst();
            if (span.isPresent()) {
                return span;
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }
}
