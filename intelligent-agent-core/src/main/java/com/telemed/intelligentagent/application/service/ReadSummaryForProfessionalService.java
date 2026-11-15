package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.ReadSummaryForProfessionalUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.SUMMARY_NOT_FOUND;

/**
 * Read-only. The appointment service confirms who attends the appointment;
 * another professional's appointment is reported exactly like a missing one.
 */
public class ReadSummaryForProfessionalService implements ReadSummaryForProfessionalUseCase {

    private final AppointmentsPort appointments;
    private final SummaryRepository summaries;

    public ReadSummaryForProfessionalService(AppointmentsPort appointments, SummaryRepository summaries) {
        this.appointments = appointments;
        this.summaries = summaries;
    }

    @Override
    public PreConsultationSummary read(UUID professionalId, UUID appointmentId) {
        appointments.findById(appointmentId)
                .filter(appointment -> appointment.isAttendedBy(professionalId))
                .orElseThrow(() -> new AgentDomainException(APPOINTMENT_NOT_FOUND, "The appointment was not found."));
        return summaries.findByAppointment(appointmentId)
                .orElseThrow(() -> new AgentDomainException(SUMMARY_NOT_FOUND,
                        "The patient has not finished a pre-consultation for this appointment."));
    }
}
