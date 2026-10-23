package com.telemed.intelligentagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Prometheus scrapes the agent on the management port, which deploy publishes on 127.0.0.1 only. */
// Spring Boot turns metrics export off in tests unless asked for.
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class MetricsEndpointTest {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void exposesTheWeek11MetricsOnTheManagementPort() {
        http.getForEntity("http://localhost:" + port + "/api/v1/agent/conversations/x", String.class);

        ResponseEntity<String> scrape = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("agent_requests_total{")
                .contains("agent_request_duration_seconds_bucket{")
                .contains("agent_outbox_pending_events{event_type=\"PreConsultationSummaryGenerated\"}");
    }

    @Test
    void neverServesMetricsOnThePublicPort() {
        ResponseEntity<String> scrape = http.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode().is4xxClientError()).isTrue();
        assertThat(scrape.getBody()).doesNotContain("agent_requests_total");
    }
}
