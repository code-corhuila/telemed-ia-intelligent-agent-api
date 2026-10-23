package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** HU-06: the patient reads their own pre-consultation, by id or by appointment. */
public interface GetPreConsultationUseCase {

    PreConsultationView get(UUID patientId, UUID conversationId);

    PreConsultationView getByAppointment(UUID patientId, UUID appointmentId);

    /** The summary is present once the pre-consultation has finished. */
    record PreConsultationView(Conversation conversation, List<Message> messages,
                               Optional<PreConsultationSummary> summary) {
    }
}
