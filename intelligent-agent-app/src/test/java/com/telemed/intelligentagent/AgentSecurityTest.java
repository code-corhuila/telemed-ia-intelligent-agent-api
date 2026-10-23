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

import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static org.assertj.core.api.Assertions.assertThat;

/** Only patients with a token signed by identity-api reach the agent. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class AgentSecurityTest {

    private static final KeyPair OTHER_KEYS = TestIdentityKeys.rsaKeyPair();
    private static final String ANY_AGENT_PATH = "/api/v1/agent/conversations";
    private static final String SUBJECT = UUID.randomUUID().toString();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void identityPublicKey(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void rejectsRequestsWithoutAToken() {
        ResponseEntity<String> response = get(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"").contains("\"traceId\"");
    }

    @Test
    void rejectsTokensNotSignedByIdentity() {
        assertThat(get(token(OTHER_KEYS, ISSUER, SUBJECT, "PATIENT", inTenMinutes())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokensFromAnotherIssuer() {
        assertThat(get(token(IDENTITY, "someone-else", SUBJECT, "PATIENT", inTenMinutes())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsExpiredTokens() {
        assertThat(get(token(IDENTITY, ISSUER, SUBJECT, "PATIENT", Instant.now().minusSeconds(600))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void forbidsValidTokensOfOtherRoles() {
        ResponseEntity<String> response = get(token(IDENTITY, ISSUER, SUBJECT, "PROFESSIONAL", inTenMinutes()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"error\":\"FORBIDDEN\"");
    }

    @Test
    void letsPatientsThrough() {
        // No endpoint is mapped yet: past security, the request simply finds nothing.
        assertThat(get(token(IDENTITY, ISSUER, SUBJECT, "PATIENT", inTenMinutes())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void echoesTheCallersCorrelationIdAsTraceId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "corr-123");

        ResponseEntity<String> response = http.exchange(
                ANY_AGENT_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getBody()).contains("\"traceId\":\"corr-123\"");
    }

    private ResponseEntity<String> get(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(ANY_AGENT_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static Instant inTenMinutes() {
        return Instant.now().plusSeconds(600);
    }
}
