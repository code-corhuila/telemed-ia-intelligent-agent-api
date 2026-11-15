package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.SummaryDraft;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.safety.ClinicalSafetyPolicy;
import com.telemed.intelligentagent.domain.safety.PersonalDataRedactor;

import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;

/**
 * DEC-019, inside ADR-008: the model only rewrites what the patient said, redacted; the
 * professional's record never reaches it. Without a usable answer the paragraph is built from
 * the same fields, so the PDF never waits on the AI.
 */
public class PreConsultationReportService implements GetPreConsultationReportUseCase {

    static final int MAX_MODEL_TEXT = 900;

    private final SummaryRepository summaries;
    private final AgentModelPort model;
    private final ClinicalSafetyPolicy safetyPolicy = new ClinicalSafetyPolicy();
    private final PersonalDataRedactor redactor = new PersonalDataRedactor();

    public PreConsultationReportService(SummaryRepository summaries, AgentModelPort model) {
        this.summaries = summaries;
        this.model = model;
    }

    @Override
    public PreConsultationReport report(UUID appointmentId) {
        PreConsultationSummary summary = summaries.findByAppointment(appointmentId)
                .orElseThrow(() -> new AgentDomainException(SUMMARY_NOT_FOUND,
                        "The patient has not finished a pre-consultation for this appointment."));

        String written = modelText(new SummaryDraft(redacted(summary.consultationReason()),
                redacted(summary.evolutionTime()), redacted(summary.detailedSymptoms()),
                redacted(summary.relevantHistory())));
        return written != null
                ? new PreConsultationReport(appointmentId, written, Source.MODEL)
                : new PreConsultationReport(appointmentId, template(summary), Source.TEMPLATE);
    }

    private String modelText(SummaryDraft patientReport) {
        String text;
        try {
            text = model.reasonForVisit(patientReport);
        } catch (AgentModelUnavailableException unavailable) {
            return null;
        }
        if (text == null || text.isBlank() || text.strip().length() > MAX_MODEL_TEXT
                || safetyPolicy.crossesClinicalLine(text)) {
            return null;
        }
        return text.strip();
    }

    private String redacted(String field) {
        return field == null ? null : redactor.redact(field);
    }

    private static String template(PreConsultationSummary summary) {
        StringBuilder text = new StringBuilder("Nos contaste que consultas por: ")
                .append(sentence(summary.consultationReason()));
        append(text, "Sobre cuándo empezó, dijiste: ", summary.evolutionTime());
        append(text, "Describiste: ", summary.detailedSymptoms());
        append(text, "Antecedentes que mencionaste: ", summary.relevantHistory());
        return text.toString();
    }

    private static void append(StringBuilder text, String lead, String field) {
        if (field != null) {
            text.append(' ').append(lead).append(sentence(field));
        }
    }

    private static String sentence(String field) {
        String words = field.strip();
        while (words.endsWith(".")) {
            words = words.substring(0, words.length() - 1).strip();
        }
        return words + ".";
    }
}
