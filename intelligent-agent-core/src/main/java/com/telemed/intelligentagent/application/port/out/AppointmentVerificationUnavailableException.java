package com.telemed.intelligentagent.application.port.out;

/** The appointment service could not confirm the appointment; nothing starts and the API answers 503. */
public class AppointmentVerificationUnavailableException extends RuntimeException {

    public AppointmentVerificationUnavailableException(String message) {
        super(message);
    }
}
