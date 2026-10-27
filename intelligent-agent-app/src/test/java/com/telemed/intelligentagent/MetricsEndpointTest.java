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
    void exposesTheWeek11MetricsOnTheManagementPort() throws InterruptedException {
        http.getForEntity("http://localhost:" + port + "/api/v1/agent/conversations/x", String.class);

        ResponseEntity<String> scrape = scrapeOnceRecorded("agent_request_duration_seconds_bucket{");

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("agent_requests_total{")
                .contains("agent_request_duration_seconds_bucket{")
                .contains("agent_outbox_pending_events{event_type=\"PreConsultationSummaryGenerated\"}");
    }

    @Test
    void publishesTheBucketsTheSloRulesRead() throws InterruptedException {
        http.getForEntity("http://localhost:" + port + "/api/v1/agent/conversations/x", String.class);

        // deploy/prometheus/agent-slo-rules.yml reads these exact le values (1 s for finish, 4 s for a turn).
        assertThat(scrapeOnceRecorded("agent_request_duration_seconds_bucket{").getBody())
                .containsPattern("agent_request_duration_seconds_bucket\\{[^}]*le=\"1.0\"")
                .containsPattern("agent_request_duration_seconds_bucket\\{[^}]*le=\"4.0\"");
    }

    /**
     * The filter records a request after its response is sent, so a fast scrape can arrive
     * first; Prometheus scrapes every few seconds and never notices, a test can.
     */
    private ResponseEntity<String> scrapeOnceRecorded(String series) throws InterruptedException {
        ResponseEntity<String> scrape = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            scrape = http.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
            if (scrape.getBody() != null && scrape.getBody().contains(series)) {
                return scrape;
            }
            Thread.sleep(100);
        }
        return scrape;
    }

    @Test
    void exposesTheSaturationOfTheRequestThreads() {
        ResponseEntity<String> scrape = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        // CP4 saturation: max(tomcat_threads_busy_threads) / max(tomcat_threads_config_max_threads).
        assertThat(scrape.getBody())
                .contains("tomcat_threads_busy_threads{")
                .contains("tomcat_threads_config_max_threads{");
    }

    @Test
    void neverServesMetricsOnThePublicPort() {
        ResponseEntity<String> scrape = http.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode().is4xxClientError()).isTrue();
        assertThat(scrape.getBody()).doesNotContain("agent_requests_total");
    }
}
