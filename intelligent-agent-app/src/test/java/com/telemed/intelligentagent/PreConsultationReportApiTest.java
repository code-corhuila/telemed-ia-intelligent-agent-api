package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase.PreConsultationReport;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase.Source;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
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
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DEC-019: only document generation reads the reason for the visit, through /internal. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.agent-report.clients=document-generation"}
)
class PreConsultationReportApiTest {

    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final String PATH = "/internal/preconsultation-reports?appointmentId=" + APPOINTMENT;

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private GetPreConsultationReportUseCase reports;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void givesDocumentGenerationTheReasonForTheVisit() {
        when(reports.report(APPOINTMENT)).thenReturn(
                new PreConsultationReport(APPOINTMENT, "Nos contaste que te duele la cabeza.", Source.MODEL));

        ResponseEntity<String> response = get(PATH, "document-generation", "SERVICE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"data\":{", "\"appointmentId\":\"" + APPOINTMENT + "\"")
                .contains("\"reasonForVisit\":\"Nos contaste que te duele la cabeza.\"", "\"source\":\"model\"");
    }

    @Test
    void forbidsOtherServicesAndUsers() {
        for (List<String> caller : List.of(List.of("notifications", "SERVICE"),
                List.of(UUID.randomUUID().toString(), "PROFESSIONAL"),
                List.of(UUID.randomUUID().toString(), "PATIENT"),
                List.of("document-generation", "ADMIN"))) {
            assertThat(get(PATH, caller.get(0), caller.get(1)).getStatusCode()).as(caller.toString())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        verifyNoInteractions(reports);
    }

    @Test
    void needsAToken() {
        assertThat(http.getForEntity(PATH, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reportsAMissingPreConsultationAsNotFound() {
        doThrow(new AgentDomainException(SUMMARY_NOT_FOUND, "x")).when(reports).report(any());

        ResponseEntity<String> response = get(PATH, "document-generation", "SERVICE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"error\":\"SUMMARY_NOT_FOUND\"");
    }

    @Test
    void needsTheAppointment() {
        assertThat(get("/internal/preconsultation-reports", "document-generation", "SERVICE").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> get(String path, String subject, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(IDENTITY, ISSUER, subject, role, Instant.now().plusSeconds(600)));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
