package com.telemed.intelligentagent.adapter.out.persistence.mongo;

import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.ConversationStatus;
import com.telemed.intelligentagent.domain.model.FinalizationReason;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.PRECONSULTATION_ALREADY_EXISTS;

/**
 * agent-db's conversations, written field by field: its validator rejects any
 * field it does not declare, so no mapping metadata is ever stored.
 */
public class MongoConversationRepository implements ConversationRepository {

    static final String COLLECTION = "conversations";

    private final MongoTemplate mongo;
    private final Clock clock;

    public MongoConversationRepository(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        return findOne(Criteria.where("_id").is(id.toString()));
    }

    @Override
    public Optional<Conversation> findByAppointment(UUID appointmentId) {
        return findOne(Criteria.where("appointmentId").is(appointmentId.toString()));
    }

    @Override
    public boolean existsForAppointment(UUID appointmentId) {
        return mongo.exists(Query.query(Criteria.where("appointmentId").is(appointmentId.toString())), COLLECTION);
    }

    @Override
    public boolean hasActiveFor(UUID patientId) {
        return mongo.exists(Query.query(Criteria.where("patientId").is(patientId.toString())
                .and("status").is(ConversationStatus.ACTIVE.name())), COLLECTION);
    }

    @Override
    public void save(Conversation conversation) {
        Update update = new Update()
                .set("patientId", conversation.patientId().toString())
                .set("appointmentId", conversation.appointmentId().toString())
                .set("startDateTime", Date.from(conversation.startedAt()))
                .set("status", conversation.status().name())
                .set("questionLimit", conversation.questionLimit())
                .set("questionsAsked", conversation.questionsAsked())
                .set("updatedAt", Date.from(clock.instant()))
                .setOnInsert("createdAt", Date.from(clock.instant()));
        if (conversation.endedAt() != null) {
            update.set("endDateTime", Date.from(conversation.endedAt()));
        }
        if (conversation.finalizationReason() != null) {
            update.set("finalizationReason", conversation.finalizationReason().name());
        }
        try {
            mongo.upsert(Query.query(Criteria.where("_id").is(conversation.id().toString())), update, COLLECTION);
        } catch (DuplicateKeyException duplicate) {
            throw uniquenessViolation(duplicate);
        }
    }

    private Optional<Conversation> findOne(Criteria criteria) {
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), Document.class, COLLECTION))
                .map(MongoConversationRepository::toConversation);
    }

    private static Conversation toConversation(Document document) {
        String reason = document.getString("finalizationReason");
        return Conversation.restore(
                UUID.fromString(document.getString("_id")),
                UUID.fromString(document.getString("patientId")),
                UUID.fromString(document.getString("appointmentId")),
                ConversationStatus.valueOf(document.getString("status")),
                document.getInteger("questionLimit"),
                document.getInteger("questionsAsked"),
                instant(document.getDate("startDateTime")),
                instant(document.getDate("endDateTime")),
                reason == null ? null : FinalizationReason.valueOf(reason));
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    /** The unique indexes back the service checks when two requests race past them. */
    private static RuntimeException uniquenessViolation(DuplicateKeyException duplicate) {
        String detail = String.valueOf(duplicate.getMessage());
        if (detail.contains("uq_conversations_appointment")) {
            return new AgentDomainException(PRECONSULTATION_ALREADY_EXISTS,
                    "The appointment already has a pre-consultation.");
        }
        if (detail.contains("uq_conversations_active_patient")) {
            return new AgentDomainException(ACTIVE_PRECONSULTATION_EXISTS,
                    "Another pre-consultation is still in progress.");
        }
        return duplicate;
    }
}
