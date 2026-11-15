package com.telemed.intelligentagent.application.port.in;

import java.util.UUID;

/**
 * DEC-019: the reason for the visit for the patient's consultation PDF. Read-only: the paragraph
 * is neither stored nor logged. Only allowed service clients reach it (checked at the door).
 */
public interface GetPreConsultationReportUseCase {

    /** @throws com.telemed.intelligentagent.domain.model.AgentDomainException SUMMARY_NOT_FOUND */
    PreConsultationReport report(UUID appointmentId);

    record PreConsultationReport(UUID appointmentId, String reasonForVisit, Source source) {
    }

    /** MODEL when the AI provider wrote the paragraph, TEMPLATE when it was built from the fields. */
    enum Source {
        MODEL,
        TEMPLATE
    }
}
