package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;

/**
 * agent-db's preconsultation_summaries plus the outbox (ADR: transactional
 * outbox). The summary and its PreConsultationSummaryGenerated event are
 * written in one transaction, joining the caller's if there is one.
 */
public class MongoSummaryRepository implements SummaryRepository {

    static final String COLLECTION = "preconsultation_summaries";
    static final String OUTBOX = "outbox_events";

    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Supplier<UUID> correlationIds;

    public MongoSummaryRepository(MongoTemplate mongo, TransactionTemplate transaction, Clock clock,
                                  Supplier<UUID> correlationIds) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.clock = clock;
        this.correlationIds = correlationIds;
    }

    @Override
    public Optional<PreConsultationSummary> findByConversation(UUID conversationId) {
        Query query = Query.query(Criteria.where("conversationId").is(conversationId.toString()));
        return Optional.ofNullable(mongo.findOne(query, Document.class, COLLECTION))
                .map(MongoSummaryRepository::toSummary);
    }

    @Override
    public void save(PreConsultationSummary summary) {
        try {
            transaction.executeWithoutResult(status -> {
                mongo.insert(toDocument(summary), COLLECTION);
                mongo.insert(generatedEvent(summary), OUTBOX);
            });
        } catch (DuplicateKeyException duplicate) {
            String detail = String.valueOf(duplicate.getMessage());
            if (detail.contains("uq_summaries_conversation") || detail.contains("uq_summaries_appointment")) {
                throw new AgentDomainException(CONVERSATION_FINISHED, "The pre-consultation has already finished.");
            }
            throw duplicate;
        }
    }

    private static Document toDocument(PreConsultationSummary summary) {
        Document document = new Document("_id", summary.id().toString())
                .append("conversationId", summary.conversationId().toString())
                .append("appointmentId", summary.appointmentId().toString())
                .append("patientId", summary.patientId().toString())
                .append("consultationReason", summary.consultationReason());
        // agent-db accepts the optional fields only when present and non-empty.
        appendIfPresent(document, "evolutionTime", summary.evolutionTime());
        appendIfPresent(document, "detailedSymptoms", summary.detailedSymptoms());
        appendIfPresent(document, "relevantHistory", summary.relevantHistory());
        return document.append("createdAt", Date.from(summary.createdAt()));
    }

    /** Ids only: the clinical content never leaves agent-db through the event. */
    private Document generatedEvent(PreConsultationSummary summary) {
        Date generatedAt = Date.from(summary.createdAt());
        return new Document("_id", UUID.randomUUID().toString())
                .append("eventType", "PreConsultationSummaryGenerated")
                .append("aggregateType", "PRECONSULTATION_SUMMARY")
                .append("aggregateId", summary.id().toString())
                .append("correlationId", correlationIds.get().toString())
                .append("status", "PENDING")
                .append("occurredAt", generatedAt)
                .append("createdAt", Date.from(clock.instant()))
                .append("payload", new Document("summaryId", summary.id().toString())
                        .append("appointmentId", summary.appointmentId().toString())
                        .append("patientId", summary.patientId().toString())
                        .append("conversationId", summary.conversationId().toString())
                        .append("generatedAt", generatedAt));
    }

    private static void appendIfPresent(Document document, String field, String value) {
        if (value != null) {
            document.append(field, value);
        }
    }

    private static PreConsultationSummary toSummary(Document document) {
        return new PreConsultationSummary(
                UUID.fromString(document.getString("_id")),
                UUID.fromString(document.getString("conversationId")),
                UUID.fromString(document.getString("appointmentId")),
                UUID.fromString(document.getString("patientId")),
                document.getString("consultationReason"),
                document.getString("evolutionTime"),
                document.getString("detailedSymptoms"),
                document.getString("relevantHistory"),
                document.getDate("createdAt").toInstant());
    }
}
