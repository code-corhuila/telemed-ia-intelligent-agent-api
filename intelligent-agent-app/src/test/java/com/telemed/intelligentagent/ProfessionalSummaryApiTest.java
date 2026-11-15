package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.in.ReadSummaryForProfessionalUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The summary is for the professional of the appointment: their own endpoint, their own role. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class ProfessionalSummaryApiTest {

    private static final UUID PROFESSIONAL = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00.123Z");
    private static final String PATH = "/api/v1/agent/preconsultations?appointmentId=" + APPOINTMENT;

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private ReadSummaryForProfessionalUseCase read;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void givesTheProfessionalTheSummaryOfTheirAppointment() {
        Conversation conversation = Conversation.start(UUID.randomUUID(), APPOINTMENT, 8, NOW);
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, "Dolor de garganta", "Tres días",
                null, "Alergia a la penicilina", NOW);
        when(read.read(PROFESSIONAL, APPOINTMENT)).thenReturn(summary);

        ResponseEntity<String> response = get(PATH, "PROFESSIONAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"id\":\"" + summary.id() + "\"", "\"appointmentId\":\"" + APPOINTMENT + "\"")
                .contains("\"consultationReason\":\"Dolor de garganta\"", "\"evolutionTime\":\"Tres días\"")
                .contains("\"detailedSymptoms\":null", "\"relevantHistory\":\"Alergia a la penicilina\"")
                .contains("\"createdAt\":\"2026-10-23T15:00:00.123Z\"");
    }

    @Test
    void neverGivesItToThePatient() {
        ResponseEntity<String> response = get(PATH, "PATIENT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(read);
    }

    @Test
    void keepsTheProfessionalOutOfThePatientConversations() {
        ResponseEntity<String> response = get("/api/v1/agent/conversations?appointmentId=" + APPOINTMENT,
                "PROFESSIONAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reportsMissingSummariesAndAppointmentsAsNotFound() {
        doThrow(new AgentDomainException(SUMMARY_NOT_FOUND, "x")).when(read).read(any(), any());
        assertThat(get(PATH, "PROFESSIONAL").getBody()).contains("\"error\":\"SUMMARY_NOT_FOUND\"");

        doThrow(new AgentDomainException(APPOINTMENT_NOT_FOUND, "x")).when(read).read(any(), any());
        ResponseEntity<String> foreign = get(PATH, "PROFESSIONAL");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).contains("\"error\":\"APPOINTMENT_NOT_FOUND\"");

        doThrow(new AppointmentVerificationUnavailableException("x")).when(read).read(any(), any());
        assertThat(get(PATH, "PROFESSIONAL").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void needsTheAppointment() {
        assertThat(get("/api/v1/agent/preconsultations", "PROFESSIONAL").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> get(String path, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(IDENTITY, ISSUER, PROFESSIONAL.toString(), role, Instant.now().plusSeconds(600)));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
