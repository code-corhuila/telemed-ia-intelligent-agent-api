package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.domain.model.Conversation;

import java.util.Optional;
import java.util.UUID;

/** agent-db's conversations collection. */
public interface ConversationRepository {

    Optional<Conversation> findById(UUID id);

    boolean existsForAppointment(UUID appointmentId);

    boolean hasActiveFor(UUID patientId);

    void save(Conversation conversation);
}
