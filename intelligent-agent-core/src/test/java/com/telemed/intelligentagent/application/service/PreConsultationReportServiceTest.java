package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase.PreConsultationReport;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase.Source;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-019: the reason for the visit, written for the patient, inside ADR-008. */
class PreConsultationReportServiceTest {

    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");

    private final InMemorySummaries summaries = new InMemorySummaries();
    private final WritingModel model = new WritingModel();
    private final PreConsultationReportService service = new PreConsultationReportService(summaries, model);

    @Test
    void usesTheParagraphTheModelWroteFromThePatientsOwnFields() {
        storeSummary("Dolor de cabeza, mi celular es 3105550101", "Dos días", "Pulsátil", "Migraña en la familia");
        model.answer = "Nos contaste que tienes dolor de cabeza desde hace dos días.";

        PreConsultationReport report = service.report(APPOINTMENT);

        assertThat(report).isEqualTo(new PreConsultationReport(APPOINTMENT,
                "Nos contaste que tienes dolor de cabeza desde hace dos días.", Source.MODEL));
        assertThat(model.received.consultationReason()).doesNotContain("3105550101");
        assertThat(model.received.evolutionTime()).isEqualTo("Dos días");
        assertThat(model.received.detailedSymptoms()).isEqualTo("Pulsátil");
        assertThat(model.received.relevantHistory()).isEqualTo("Migraña en la familia");
    }

    @Test
    void buildsTheParagraphFromTheFieldsWhenTheModelWritesNoText() {
        storeSummary("Dolor de garganta.", "Tres días", null, "Alergia a la penicilina");
        model.answer = null;

        PreConsultationReport report = service.report(APPOINTMENT);

        assertThat(report.source()).isEqualTo(Source.TEMPLATE);
        assertThat(report.reasonForVisit())
                .startsWith("Nos contaste que consultas por: Dolor de garganta.")
                .contains("Tres días", "Alergia a la penicilina")
                .doesNotContain("..", "null");
    }

    @Test
    void fallsBackToTheTemplateWhenTheProviderFails() {
        storeSummary("Dolor de garganta", null, null, null);
        model.failure = new AgentModelUnavailableException("down");

        assertThat(service.report(APPOINTMENT))
                .isEqualTo(new PreConsultationReport(APPOINTMENT,
                        "Nos contaste que consultas por: Dolor de garganta.", Source.TEMPLATE));
    }

    @Test
    void rejectsAnAnswerThatIsEmptyTooLongOrClinical() {
        storeSummary("Dolor de garganta", null, null, null);

        for (String answer : List.of("   ", "a".repeat(901), "Te recomiendo tomar acetaminofén cada 8 horas.")) {
            model.answer = answer;
            assertThat(service.report(APPOINTMENT).source()).as(answer).isEqualTo(Source.TEMPLATE);
        }
    }

    @Test
    void saysWhenTheAppointmentHasNoPreConsultation() {
        assertThatThrownBy(() -> service.report(APPOINTMENT))
                .isInstanceOf(AgentDomainException.class)
                .extracting(e -> ((AgentDomainException) e).reason())
                .isEqualTo(SUMMARY_NOT_FOUND);
    }

    private void storeSummary(String reason, String evolution, String symptoms, String history) {
        Conversation conversation = Conversation.start(UUID.randomUUID(), APPOINTMENT, 8, NOW);
        summaries.save(PreConsultationSummary.of(conversation, reason, evolution, symptoms, history, NOW));
    }

    private static final class WritingModel implements AgentModelPort {

        String answer;
        RuntimeException failure;
        SummaryDraft received;

        @Override
        public AgentReply nextTurn(List<Turn> transcript) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SummaryDraft summarize(List<Turn> transcript) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String reasonForVisit(SummaryDraft patientReport) {
            received = patientReport;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }
}
