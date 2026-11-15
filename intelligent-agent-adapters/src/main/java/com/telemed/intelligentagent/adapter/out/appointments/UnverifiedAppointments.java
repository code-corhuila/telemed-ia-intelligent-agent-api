package com.telemed.intelligentagent.adapter.out.appointments;

import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.domain.model.Appointment;

import java.util.Optional;
import java.util.UUID;

/** Fails closed until the appointment service adapter exists: no appointment is ever assumed valid. */
public class UnverifiedAppointments implements AppointmentsPort {

    @Override
    public Optional<Appointment> findById(UUID appointmentId) {
        throw new AppointmentVerificationUnavailableException("Appointments cannot be verified yet.");
    }
}
