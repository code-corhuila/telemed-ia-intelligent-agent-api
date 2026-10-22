package com.telemed.intelligentagent.domain.model;

/** A rule of the pre-consultation was broken; the reason maps to an API error code. */
public class AgentDomainException extends RuntimeException {

    public enum Reason {
        INVALID_CONVERSATION,
        INVALID_MESSAGE,
        MESSAGE_TOO_LONG,
        CONVERSATION_FINISHED,
        QUESTION_LIMIT_REACHED,
        CONVERSATION_NOT_FOUND,
        APPOINTMENT_NOT_FOUND,
        APPOINTMENT_NOT_ELIGIBLE,
        PRECONSULTATION_ALREADY_EXISTS,
        ACTIVE_PRECONSULTATION_EXISTS
    }

    private final Reason reason;

    public AgentDomainException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
