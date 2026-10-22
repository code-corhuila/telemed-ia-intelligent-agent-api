package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.Optional;
import java.util.UUID;

/**
 * agent-db's preconsultation_summaries collection. Saving a summary also
 * records its PreConsultationSummaryGenerated outbox event, atomically.
 */
public interface SummaryRepository {

    Optional<PreConsultationSummary> findByConversation(UUID conversationId);

    void save(PreConsultationSummary summary);
}
