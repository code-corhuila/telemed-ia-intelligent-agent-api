package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.domain.model.Appointment;

import java.util.Optional;
import java.util.UUID;

/** The appointment service, read-only: the agent never changes an appointment. */
public interface AppointmentsPort {

    Optional<Appointment> findById(UUID appointmentId);
}
