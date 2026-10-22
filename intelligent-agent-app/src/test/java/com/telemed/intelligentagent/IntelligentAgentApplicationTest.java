package com.telemed.intelligentagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class IntelligentAgentApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void reportsHealthOnTheManagementPortOnly() {
        ResponseEntity<String> health = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class);
        ResponseEntity<String> healthOnPublicPort = http.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(healthOnPublicPort.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exposesNoActuatorEndpointBeyondHealthAndInfo() {
        ResponseEntity<String> env = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/env", String.class);

        assertThat(env.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
