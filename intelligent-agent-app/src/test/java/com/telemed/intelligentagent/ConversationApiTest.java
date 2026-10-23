package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase.PreConsultationView;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_ELIGIBLE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The conversations API: the contract's shapes, the patient from the token, and the common error envelope. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class ConversationApiTest {

    private static final String BASE = "/api/v1/agent/conversations";
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private StartPreConsultationUseCase start;

    @MockitoBean
    private GetPreConsultationUseCase get;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void startsThePreConsultationOfTheAppointmentForThePatientInTheToken() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 8, NOW);
        when(start.start(PATIENT, APPOINTMENT)).thenReturn(conversation);
        when(get.get(PATIENT, conversation.id())).thenReturn(viewOf(conversation, Optional.empty()));

        // A patientId in the body is ignored: the patient is always the token's subject.
        ResponseEntity<String> response = post(BASE, """
                {"appointmentId":"%s","patientId":"%s"}""".formatted(APPOINTMENT, UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasToString(BASE + "/" + conversation.id());
        assertThat(response.getBody())
                .contains("\"id\":\"" + conversation.id() + "\"")
                .contains("\"patientId\":\"" + PATIENT + "\"")
                .contains("\"appointmentId\":\"" + APPOINTMENT + "\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"startDateTime\":\"2026-10-22T15:00:00.123Z\"")
                .contains("\"questionLimit\":8", "\"questionsAsked\":0")
                .contains("\"sender\":\"AGENT\"", "\"content\":\"¿Qué te trae?\"")
                .contains("\"preconsultationSummary\":null");
        verify(start).start(PATIENT, APPOINTMENT);
    }

    @Test
    void needsAnIdempotencyKeyAndAValidAppointmentToStart() {
        ResponseEntity<String> noKey = exchange(HttpMethod.POST, BASE, null,
                "{\"appointmentId\":\"" + APPOINTMENT + "\"}");
        ResponseEntity<String> badId = post(BASE, "{\"appointmentId\":\"not-a-uuid\"}");
        ResponseEntity<String> noId = post(BASE, "{}");

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getBody()).contains("\"error\":\"VALIDATION_ERROR\"", "\"field\":\"Idempotency-Key\"");
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noId.getBody()).contains("\"error\":\"VALIDATION_ERROR\"", "\"field\":\"appointmentId\"");
        verifyNoInteractions(start);
    }

    @Test
    void reportsTheAppointmentRulesWithTheCommonEnvelope() {
        assertStartFails(new AgentDomainException(APPOINTMENT_NOT_FOUND, "x"), HttpStatus.NOT_FOUND,
                "APPOINTMENT_NOT_FOUND");
        assertStartFails(new AgentDomainException(APPOINTMENT_NOT_ELIGIBLE, "x"), HttpStatus.UNPROCESSABLE_ENTITY,
                "APPOINTMENT_NOT_ELIGIBLE");
        assertStartFails(new AgentDomainException(ACTIVE_PRECONSULTATION_EXISTS, "x"), HttpStatus.CONFLICT,
                "ACTIVE_PRECONSULTATION_EXISTS");
        assertStartFails(new AppointmentVerificationUnavailableException("x"), HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE");
    }

    @Test
    void showsTheirOwnConversationWithItsSummaryOnceFinished() {
        Conversation conversation = Conversation.start(PATIENT, APPOINTMENT, 8, NOW);
        conversation.finish(NOW.plusSeconds(60));
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, "Dolor de cabeza", null, null,
                null, NOW.plusSeconds(60));
        PreConsultationView view = viewOf(conversation, Optional.of(summary));
        when(get.get(PATIENT, conversation.id())).thenReturn(view);
        when(get.getByAppointment(PATIENT, APPOINTMENT)).thenReturn(view);

        ResponseEntity<String> byId = exchange(HttpMethod.GET, BASE + "/" + conversation.id(), null, null);
        ResponseEntity<String> byAppointment = exchange(HttpMethod.GET, BASE + "?appointmentId=" + APPOINTMENT,
                null, null);

        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody())
                .contains("\"status\":\"FINISHED\"", "\"finalizationReason\":\"COMPLETED\"")
                .contains("\"endDateTime\":\"2026-10-22T15:01:00.123Z\"")
                .contains("\"consultationReason\":\"Dolor de cabeza\"", "\"detailedSymptoms\":null");
        assertThat(byAppointment.getBody()).isEqualTo(byId.getBody());
    }

    @Test
    void answersNotFoundForAnotherPatientsConversation() {
        UUID foreign = UUID.randomUUID();
        when(get.get(eq(PATIENT), eq(foreign))).thenThrow(new AgentDomainException(CONVERSATION_NOT_FOUND, "x"));

        ResponseEntity<String> response = exchange(HttpMethod.GET, BASE + "/" + foreign, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"error\":\"CONVERSATION_NOT_FOUND\"", "\"traceId\":\"trace-0001\"");
    }

    private void assertStartFails(RuntimeException failure, HttpStatus status, String error) {
        doThrow(failure).when(start).start(any(), any());

        ResponseEntity<String> response = post(BASE, "{\"appointmentId\":\"" + APPOINTMENT + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"error\":\"" + error + "\"", "\"traceId\":\"trace-0001\"");
    }

    private ResponseEntity<String> post(String path, String body) {
        return exchange(HttpMethod.POST, path, "start-key-0001", body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(IDENTITY, ISSUER, PATIENT.toString(), "PATIENT", Instant.now().plusSeconds(600)));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "trace-0001");
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static PreConsultationView viewOf(Conversation conversation, Optional<PreConsultationSummary> summary) {
        Message opening = Message.of(conversation.id(), Sender.AGENT, "opening-" + conversation.id(),
                "¿Qué te trae?", NOW);
        return new PreConsultationView(conversation, List.of(opening), summary);
    }
}
