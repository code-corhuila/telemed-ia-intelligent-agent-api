package com.telemed.intelligentagent.application.port.out;

/** The broker did not take an event; it stays pending and the next relay run retries it. */
public class EventPublicationException extends RuntimeException {

    public EventPublicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
