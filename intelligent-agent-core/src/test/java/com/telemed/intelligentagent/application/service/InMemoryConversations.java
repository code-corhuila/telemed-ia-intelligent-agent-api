package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.domain.model.Conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double of the conversations collection. */
class InMemoryConversations implements ConversationRepository {

    final Map<UUID, Conversation> stored = new LinkedHashMap<>();

    @Override
    public Optional<Conversation> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    @Override
    public Optional<Conversation> findByAppointment(UUID appointmentId) {
        return stored.values().stream().filter(c -> c.appointmentId().equals(appointmentId)).findFirst();
    }

    @Override
    public boolean existsForAppointment(UUID appointmentId) {
        return findByAppointment(appointmentId).isPresent();
    }

    @Override
    public boolean hasActiveFor(UUID patientId) {
        return stored.values().stream().anyMatch(c -> c.belongsTo(patientId) && c.isActive());
    }

    @Override
    public void save(Conversation conversation) {
        stored.put(conversation.id(), conversation);
    }
}
