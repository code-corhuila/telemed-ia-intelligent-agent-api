package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;

import java.util.List;
import java.util.UUID;

/** HU-06: the patient reads their own pre-consultation, by id or by appointment. */
public interface GetPreConsultationUseCase {

    PreConsultationView get(UUID patientId, UUID conversationId);

    PreConsultationView getByAppointment(UUID patientId, UUID appointmentId);

    /** What the patient may see. The summary is not here: it goes to the professional only. */
    record PreConsultationView(Conversation conversation, List<Message> messages) {
    }
}
