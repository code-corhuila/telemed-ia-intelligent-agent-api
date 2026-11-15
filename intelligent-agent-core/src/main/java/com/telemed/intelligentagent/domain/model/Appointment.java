package com.telemed.intelligentagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** What the agent needs to know about an appointment, as the appointment service reports it. */
public record Appointment(UUID id, UUID patientId, UUID professionalId, AppointmentStatus status,
                          Instant startsAt) {

    public boolean belongsTo(UUID patient) {
        return patientId.equals(patient);
    }

    /** The professional who will attend it: the only one who may read its pre-consultation summary. */
    public boolean isAttendedBy(UUID professional) {
        return professionalId.equals(professional);
    }

    /** First the appointment, then the pre-consultation: only while the appointment is still to happen. */
    public boolean acceptsPreConsultation(Instant now) {
        return (status == AppointmentStatus.CONFIRMED || status == AppointmentStatus.RESCHEDULED)
                && startsAt.isAfter(now);
    }
}
