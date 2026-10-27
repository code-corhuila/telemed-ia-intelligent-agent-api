package com.telemed.intelligentagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static org.assertj.core.api.Assertions.assertThat;

/** With agent-db unreachable the patient gets the 503 envelope quickly, not a 500 after the driver's 30 s. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                // Nothing listens on port 1: every server selection times out.
                "MONGODB_URI=mongodb://127.0.0.1:1/intelligent_agent?replicaSet=rs0",
                "agent.mongo.server-selection-timeout=300ms",
                "agent.mongo.connect-timeout=300ms"
        }
)
class MongoFailFastTest {

    private static final UUID PATIENT = UUID.randomUUID();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
    }

    @Test
    void answersServiceUnavailableQuicklyWhenAgentDbDoesNotAnswer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(IDENTITY, ISSUER, PATIENT.toString(), "PATIENT", Instant.now().plusSeconds(600)));

        Instant started = Instant.now();
        ResponseEntity<String> response = http.exchange("/api/v1/agent/conversations/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("\"error\":\"SERVICE_UNAVAILABLE\"", "\"traceId\"");
    }
}
