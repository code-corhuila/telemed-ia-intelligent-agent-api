package com.telemed.intelligentagent.application.port.in;

/** New pre-consultations are paused by the operators (runbook mitigation 1); the appointment goes on without one. */
public class PreConsultationsPausedException extends RuntimeException {

    public PreConsultationsPausedException() {
        super("New pre-consultations are paused.");
    }
}
