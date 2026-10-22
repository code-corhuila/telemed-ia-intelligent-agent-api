package com.telemed.intelligentagent.application.port.out;

/** The AI provider could not give a usable answer; the API answers 503. */
public class AgentModelUnavailableException extends RuntimeException {

    public AgentModelUnavailableException(String message) {
        super(message);
    }
}
