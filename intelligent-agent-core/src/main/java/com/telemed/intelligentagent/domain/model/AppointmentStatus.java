package com.telemed.intelligentagent.domain.model;

/** Same values as appointment-scheduling-db's chk_appointments_status. */
public enum AppointmentStatus {
    CONFIRMED,
    RESCHEDULED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
