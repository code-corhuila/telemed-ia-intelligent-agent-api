package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.UUID;

/** HU-06 / HU-08: the professional reads the pre-consultation summary before attending the appointment. */
public interface ReadSummaryForProfessionalUseCase {

    /** {@code professionalId} always comes from the caller's token. */
    PreConsultationSummary read(UUID professionalId, UUID appointmentId);
}
