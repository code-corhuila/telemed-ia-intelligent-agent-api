package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 11 workshop (03-instrumentation, TracePropagationTest) on the real service: the trace that
 * reaches POST /finish is the one stored with PreConsultationSummaryGenerated in agent-db's outbox,
 * so the relay can carry it across RabbitMQ.
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class TracePropagationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final UUID PATIENT = UUID.randomUUID();

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private MeterRegistry registry;

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @BeforeEach
    void emptyAgentDb() {
        mongo.getDb().drop();
    }

    @Test
    void theTraceReceivedIsTheOneStoredWithTheOutboxEvent() {
        Conversation conversation = activeConversation();

        ResponseEntity<String> finished = finish(conversation.id(), INCOMING);

        assertThat(finished.getStatusCode()).isEqualTo(HttpStatus.OK);
        String answered = finished.getHeaders().getFirst("traceparent");
        assertThat(traceIdOf(answered)).isEqualTo(TRACE_ID);
        Document event = mongo.getCollection("outbox_events").find().first();
        assertThat(event.getString("eventType")).isEqualTo("PreConsultationSummaryGenerated");
        // Same trace, and the agent's own span is the parent of whatever publishes the event.
        assertThat(event.getString("traceparent")).isEqualTo(answered);
    }

    @Test
    void startsANewTraceWhenTheCallerSentNone() {
        Conversation conversation = activeConversation();

        ResponseEntity<String> finished = finish(conversation.id(), null);

        String traceId = traceIdOf(mongo.getCollection("outbox_events").find().first().getString("traceparent"));
        assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID)
                .isEqualTo(traceIdOf(finished.getHeaders().getFirst("traceparent")));
    }

    @Test
    void finishingTwiceKeepsTheFirstTraceAndIsMeasured() throws InterruptedException {
        Conversation conversation = activeConversation();
        double before = finishedSuccessfully();
        finish(conversation.id(), INCOMING);

        // The real API is idempotent here (the workshop prototype answered 409): same summary, no new event.
        ResponseEntity<String> again = finish(conversation.id(), "00-" + "a".repeat(32) + "-00f067aa0ba902b7-01");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mongo.getCollection("outbox_events").countDocuments()).isEqualTo(1);
        assertThat(traceIdOf(mongo.getCollection("outbox_events").find().first().getString("traceparent")))
                .isEqualTo(TRACE_ID);
        assertThat(finishedSuccessfullyOnceAtLeast(before + 2)).isEqualTo(before + 2);
        assertThat(http.getForObject("http://localhost:" + managementPort + "/actuator/prometheus", String.class))
                .contains("agent_requests_total{method=\"POST\",outcome=\"success\","
                        + "route=\"/api/v1/agent/conversations/{id}/finish\"}");
    }

    private Conversation activeConversation() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, Instant.now());
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-" + conversation.id(), "¿Qué te trae?",
                Instant.now()));
        messages.save(Message.of(conversation.id(), Sender.PATIENT, "patient-" + conversation.id(),
                "Me duele la cabeza", Instant.now()));
        return conversation;
    }

    private ResponseEntity<String> finish(UUID conversationId, String traceparent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityKeys.patientToken(PATIENT));
        if (traceparent != null) {
            headers.set("traceparent", traceparent);
        }
        return http.exchange("http://localhost:" + port + "/api/v1/agent/conversations/" + conversationId + "/finish",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    /** agent_requests_total for successful finishes; the tests share one registry, so they compare deltas. */
    private double finishedSuccessfully() {
        Counter counter = registry.find("agent.requests")
                .tags("route", "/api/v1/agent/conversations/{id}/finish", "outcome", "success")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** The metrics filter records a request after its response is sent, so the count may lag briefly. */
    private double finishedSuccessfullyOnceAtLeast(double expected) throws InterruptedException {
        for (int attempt = 0; attempt < 20 && finishedSuccessfully() < expected; attempt++) {
            Thread.sleep(100);
        }
        return finishedSuccessfully();
    }

    private static String traceIdOf(String traceparent) {
        return traceparent.split("-")[1];
    }
}
