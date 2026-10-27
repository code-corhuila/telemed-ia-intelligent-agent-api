package com.telemed.intelligentagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runbook mitigation 1: AGENT_PRECONSULTATIONS_PAUSED=true refuses new pre-consultations, nothing else. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "agent.preconsultations-paused=true"}
)
class PausedPreConsultationsTest {

    private static final UUID PATIENT = UUID.randomUUID();

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void answersANewPreConsultationWithPreconsultationPaused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityKeys.patientToken(PATIENT));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "start-key-0001");

        ResponseEntity<String> response = http.postForEntity("http://localhost:" + port + "/api/v1/agent/conversations",
                new HttpEntity<>("{\"appointmentId\":\"" + UUID.randomUUID() + "\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("\"error\":\"PRECONSULTATION_PAUSED\"", "\"traceId\":");
    }
}
