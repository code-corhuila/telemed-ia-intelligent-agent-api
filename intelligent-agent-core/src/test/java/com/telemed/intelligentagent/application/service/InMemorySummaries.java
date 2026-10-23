package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test double of the preconsultation_summaries collection. */
class InMemorySummaries implements SummaryRepository {

    final List<PreConsultationSummary> stored = new ArrayList<>();

    @Override
    public Optional<PreConsultationSummary> findByConversation(UUID conversationId) {
        return stored.stream().filter(s -> s.conversationId().equals(conversationId)).findFirst();
    }

    @Override
    public Optional<PreConsultationSummary> findByAppointment(UUID appointmentId) {
        return stored.stream().filter(s -> s.appointmentId().equals(appointmentId)).findFirst();
    }

    @Override
    public void save(PreConsultationSummary summary) {
        stored.add(summary);
    }
}
