package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.UUID;

/** HU-06: the patient ends the pre-consultation and the summary is generated. */
public interface FinishPreConsultationUseCase {

    /** Finishing again returns the summary already generated. */
    PreConsultationSummary finish(UUID patientId, UUID conversationId);
}
