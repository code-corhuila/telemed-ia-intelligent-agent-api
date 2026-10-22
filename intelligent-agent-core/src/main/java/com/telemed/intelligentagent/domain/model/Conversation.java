package com.telemed.intelligentagent.domain.model;

import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_CONVERSATION;

/**
 * A patient's pre-consultation for one appointment. The agent may ask at
 * most {@code questionLimit} questions; reaching the limit ends it.
 * Same invariants as the agent-db validators.
 */
public final class Conversation {

    private final UUID id;
    private final UUID patientId;
    private final UUID appointmentId;
    private final int questionLimit;
    private final Instant startedAt;
    private ConversationStatus status;
    private int questionsAsked;
    private Instant endedAt;
    private FinalizationReason finalizationReason;

    private Conversation(UUID id, UUID patientId, UUID appointmentId, ConversationStatus status,
                         int questionLimit, int questionsAsked, Instant startedAt, Instant endedAt,
                         FinalizationReason finalizationReason) {
        if (id == null || patientId == null || appointmentId == null || status == null
                || startedAt == null || questionLimit < 1 || questionsAsked < 0
                || questionsAsked > questionLimit) {
            throw new AgentDomainException(INVALID_CONVERSATION,
                    "A conversation needs a patient, an appointment and a positive question limit.");
        }
        this.id = id;
        this.patientId = patientId;
        this.appointmentId = appointmentId;
        this.status = status;
        this.questionLimit = questionLimit;
        this.questionsAsked = questionsAsked;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.finalizationReason = finalizationReason;
    }

    public static Conversation start(UUID patientId, UUID appointmentId, int questionLimit, Instant now) {
        return new Conversation(UUID.randomUUID(), patientId, appointmentId, ConversationStatus.ACTIVE,
                questionLimit, 0, now, null, null);
    }

    public static Conversation restore(UUID id, UUID patientId, UUID appointmentId, ConversationStatus status,
                                       int questionLimit, int questionsAsked, Instant startedAt,
                                       Instant endedAt, FinalizationReason finalizationReason) {
        return new Conversation(id, patientId, appointmentId, status, questionLimit, questionsAsked,
                startedAt, endedAt, finalizationReason);
    }

    public boolean belongsTo(UUID patient) {
        return patientId.equals(patient);
    }

    public boolean isActive() {
        return status == ConversationStatus.ACTIVE;
    }

    public void ensureActive() {
        if (!isActive()) {
            throw new AgentDomainException(CONVERSATION_FINISHED, "The pre-consultation has already finished.");
        }
    }

    /** The agent asked one more question; at the limit the conversation ends. */
    public void recordAgentQuestion(Instant now) {
        ensureActive();
        questionsAsked++;
        if (questionsAsked == questionLimit) {
            end(FinalizationReason.QUESTION_LIMIT_REACHED, now);
        }
    }

    public void finish(Instant now) {
        ensureActive();
        end(FinalizationReason.COMPLETED, now);
    }

    private void end(FinalizationReason reason, Instant now) {
        status = ConversationStatus.FINISHED;
        finalizationReason = reason;
        endedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID patientId() {
        return patientId;
    }

    public UUID appointmentId() {
        return appointmentId;
    }

    public ConversationStatus status() {
        return status;
    }

    public int questionLimit() {
        return questionLimit;
    }

    public int questionsAsked() {
        return questionsAsked;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public FinalizationReason finalizationReason() {
        return finalizationReason;
    }
}
