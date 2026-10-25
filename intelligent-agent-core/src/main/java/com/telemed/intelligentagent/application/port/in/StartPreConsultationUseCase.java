package com.telemed.intelligentagent.application.port.in;

import com.telemed.intelligentagent.domain.model.Conversation;

import java.util.UUID;

/** HU-06: the patient starts the pre-consultation of one of their appointments. */
public interface StartPreConsultationUseCase {

    /**
     * {@code patientId} always comes from the caller's token, never from the request body.
     * Retrying with the same {@code idempotencyKey} returns the conversation it already started.
     */
    Conversation start(UUID patientId, UUID appointmentId, String idempotencyKey);
}
