package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(MongoSummaryRepository.class);

    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Supplier<UUID> correlationIds;
    private final Supplier<String> traceparents;

    /** traceparents gives the W3C trace context of the request being served, or null outside one. */
    public MongoSummaryRepository(MongoTemplate mongo, TransactionTemplate transaction, Clock clock,
                                  Supplier<UUID> correlationIds, Supplier<String> traceparents) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.clock = clock;
        this.correlationIds = correlationIds;
        this.traceparents = traceparents;
    }

    @Override
    public Optional<PreConsultationSummary> findByConversation(UUID conversationId) {
        return findOne(Criteria.where("conversationId").is(conversationId.toString()));
    }

    @Override
    public Optional<PreConsultationSummary> findByAppointment(UUID appointmentId) {
        return findOne(Criteria.where("appointmentId").is(appointmentId.toString()));
    }

    private Optional<PreConsultationSummary> findOne(Criteria criteria) {
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), Document.class, COLLECTION))
                .map(MongoSummaryRepository::toSummary);
    }

    @Override
    public void save(PreConsultationSummary summary) {
        Document event = generatedEvent(summary);
        try {
            transaction.executeWithoutResult(status -> {
                mongo.insert(toDocument(summary), COLLECTION);
                mongo.insert(event, OUTBOX);
            });
        } catch (DuplicateKeyException duplicate) {
            String detail = String.valueOf(duplicate.getMessage());
            if (detail.contains("uq_summaries_conversation") || detail.contains("uq_summaries_appointment")) {
                throw new AgentDomainException(CONVERSATION_FINISHED, "The pre-consultation has already finished.");
            }
            throw duplicate;
        }
        // Ids only, as in the event: the summary's clinical content is never logged.
        LOG.atInfo()
                .addKeyValue("event", "preconsultation.summary.generated")
                .addKeyValue("summary_id", summary.id().toString())
                .addKeyValue("conversation_id", summary.conversationId().toString())
                .addKeyValue("outbox_event_id", event.getString("_id"))
                .log("summary and its outbox event written");
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
        Document event = new Document("_id", UUID.randomUUID().toString())
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
        // Week 11 (CP1): the relay copies it into the broker headers, so the consumer continues the
        // trace; agent-db accepts it as an optional field (changeset hu-06-010).
        String traceparent = traceparents.get();
        if (traceparent != null) {
            event.append("traceparent", traceparent);
        }
        return event;
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
