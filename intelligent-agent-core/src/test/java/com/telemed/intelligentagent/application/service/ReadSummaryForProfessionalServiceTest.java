package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.AppointmentStatus;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HU-06 / HU-08: the summary goes to the professional of the appointment, and to nobody else. */
class ReadSummaryForProfessionalServiceTest {

    private static final UUID PROFESSIONAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");

    private final Map<UUID, Appointment> appointments = new HashMap<>();
    private final InMemorySummaries summaries = new InMemorySummaries();
    private final ReadSummaryForProfessionalService service =
            new ReadSummaryForProfessionalService(id -> Optional.ofNullable(appointments.get(id)), summaries);

    @Test
    void givesTheProfessionalTheSummaryOfTheirAppointment() {
        Appointment appointment = appointmentOf(PROFESSIONAL);
        PreConsultationSummary summary = summaryFor(appointment);

        assertThat(service.read(PROFESSIONAL, appointment.id())).isEqualTo(summary);
    }

    @Test
    void treatsAnotherProfessionalsAppointmentAsOneThatDoesNotExist() {
        Appointment appointment = appointmentOf(UUID.randomUUID());
        summaryFor(appointment);

        assertRejected(appointment.id(), APPOINTMENT_NOT_FOUND);
        assertRejected(UUID.randomUUID(), APPOINTMENT_NOT_FOUND);
    }

    @Test
    void saysWhenThePatientHasNotFinishedAPreConsultationYet() {
        assertRejected(appointmentOf(PROFESSIONAL).id(), SUMMARY_NOT_FOUND);
    }

    @Test
    void showsNothingWhenTheAppointmentCannotBeVerified() {
        ReadSummaryForProfessionalService unverified = new ReadSummaryForProfessionalService(id -> {
            throw new AppointmentVerificationUnavailableException("down");
        }, summaries);

        assertThatThrownBy(() -> unverified.read(PROFESSIONAL, UUID.randomUUID()))
                .isInstanceOf(AppointmentVerificationUnavailableException.class);
    }

    private Appointment appointmentOf(UUID professional) {
        Appointment appointment = new Appointment(UUID.randomUUID(), PATIENT, professional,
                AppointmentStatus.CONFIRMED, NOW.plusSeconds(3600));
        appointments.put(appointment.id(), appointment);
        return appointment;
    }

    private PreConsultationSummary summaryFor(Appointment appointment) {
        Conversation conversation = Conversation.start(PATIENT, appointment.id(), 8, NOW);
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, "Dolor de garganta", null, null,
                null, NOW);
        summaries.save(summary);
        return summary;
    }

    private void assertRejected(UUID appointmentId, AgentDomainException.Reason reason) {
        assertThatThrownBy(() -> service.read(PROFESSIONAL, appointmentId))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(reason);
    }
}
