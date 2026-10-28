package com.telemed.intelligentagent;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spans go to the platform collector (and from there to Jaeger) with the ids the logs and the
 * response carry, so a trace_id found in a log line opens the same request in Jaeger.
 */
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "management.otlp.tracing.export.enabled=false"}
)
class SpanExportTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CALLER_SPAN = "00f067aa0ba902b7";

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private InMemorySpanExporter spans;
    @Autowired
    private SdkTracerProvider tracerProvider;

    @LocalServerPort
    private int port;

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
    }

    @BeforeEach
    void forgetEarlierSpans() {
        spans.reset();
    }

    @Test
    void theRequestSpanContinuesTheCallersTraceWithTheIdsOfTheLogsAndTheResponse(CapturedOutput output)
            throws InterruptedException {
        ResponseEntity<String> response = getConversation("00-" + TRACE_ID + "-" + CALLER_SPAN + "-01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        SpanData request = exportedSpanOf(TRACE_ID).orElseThrow();
        assertThat(request.getParentSpanId()).isEqualTo(CALLER_SPAN);
        assertThat(response.getHeaders().getFirst("traceparent"))
                .isEqualTo("00-" + TRACE_ID + "-" + request.getSpanId() + "-01");
        assertThat(output.getOut()).contains("\"span_id\":\"" + request.getSpanId() + "\"");
    }

    @Test
    void aRequestWithoutTraceStartsOneThatIsExportedToo() throws InterruptedException {
        ResponseEntity<String> response = getConversation(null);

        String traceId = response.getHeaders().getFirst("traceparent").split("-")[1];
        assertThat(exportedSpanOf(traceId)).isPresent();
    }

    private ResponseEntity<String> getConversation(String traceparent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityKeys.patientToken(UUID.randomUUID()));
        if (traceparent != null) {
            headers.set("traceparent", traceparent);
        }
        return http.exchange("http://localhost:" + port + "/api/v1/agent/conversations/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** The span ends after the response is written and is exported in batches, so it may lag briefly. */
    private Optional<SpanData> exportedSpanOf(String traceId) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            tracerProvider.forceFlush().join(1, TimeUnit.SECONDS);
            Optional<SpanData> span = spans.getFinishedSpanItems().stream()
                    .filter(item -> item.getTraceId().equals(traceId))
                    .filter(item -> item.getParentSpanContext().isRemote() || !item.getParentSpanContext().isValid())
                    .findFirst();
            if (span.isPresent()) {
                return span;
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }
}
