package com.telemed.intelligentagent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    @Test
    void belongsOnlyToItsPatient() {
        Appointment appointment = upcoming(AppointmentStatus.CONFIRMED);

        assertThat(appointment.belongsTo(PATIENT)).isTrue();
        assertThat(appointment.belongsTo(UUID.randomUUID())).isFalse();
        assertThat(appointment.belongsTo(null)).isFalse();
    }

    @Test
    void acceptsAPreConsultationOnlyWhileItIsStillToHappen() {
        assertThat(upcoming(AppointmentStatus.CONFIRMED).acceptsPreConsultation(NOW)).isTrue();
        assertThat(upcoming(AppointmentStatus.RESCHEDULED).acceptsPreConsultation(NOW)).isTrue();

        assertThat(upcoming(AppointmentStatus.CANCELLED).acceptsPreConsultation(NOW)).isFalse();
        assertThat(upcoming(AppointmentStatus.COMPLETED).acceptsPreConsultation(NOW)).isFalse();
        assertThat(upcoming(AppointmentStatus.NO_SHOW).acceptsPreConsultation(NOW)).isFalse();
        Appointment past = new Appointment(UUID.randomUUID(), PATIENT, AppointmentStatus.CONFIRMED,
                NOW.minusSeconds(60));
        assertThat(past.acceptsPreConsultation(NOW)).isFalse();
    }

    private static Appointment upcoming(AppointmentStatus status) {
        return new Appointment(UUID.randomUUID(), PATIENT, status, NOW.plusSeconds(3600));
    }
}
