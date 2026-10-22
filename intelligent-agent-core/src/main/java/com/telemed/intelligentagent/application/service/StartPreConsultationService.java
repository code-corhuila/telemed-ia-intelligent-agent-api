package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_ELIGIBLE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.PRECONSULTATION_ALREADY_EXISTS;

/** One pre-consultation per appointment, none without one, and only for the appointment's own patient. */
public class StartPreConsultationService implements StartPreConsultationUseCase {

    public static final String OPENING_MESSAGE = "Hola, soy el asistente de preconsulta. Voy a hacerte unas "
            + "preguntas cortas para que tu médico llegue preparado a la cita. ¿Cuál es el motivo de tu consulta?";

    private final AppointmentsPort appointments;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final int questionLimit;
    private final Clock clock;

    public StartPreConsultationService(AppointmentsPort appointments, ConversationRepository conversations,
                                       MessageRepository messages, int questionLimit, Clock clock) {
        this.appointments = appointments;
        this.conversations = conversations;
        this.messages = messages;
        this.questionLimit = questionLimit;
        this.clock = clock;
    }

    @Override
    public Conversation start(UUID patientId, UUID appointmentId) {
        Instant now = clock.instant();
        // Another patient's appointment is reported exactly like a missing one, so ids cannot be probed.
        Appointment appointment = appointments.findById(appointmentId)
                .filter(found -> found.belongsTo(patientId))
                .orElseThrow(() -> new AgentDomainException(APPOINTMENT_NOT_FOUND, "The appointment was not found."));
        if (!appointment.acceptsPreConsultation(now)) {
            throw new AgentDomainException(APPOINTMENT_NOT_ELIGIBLE,
                    "The appointment no longer accepts a pre-consultation.");
        }
        if (conversations.existsForAppointment(appointmentId)) {
            throw new AgentDomainException(PRECONSULTATION_ALREADY_EXISTS,
                    "The appointment already has a pre-consultation.");
        }
        if (conversations.hasActiveFor(patientId)) {
            throw new AgentDomainException(ACTIVE_PRECONSULTATION_EXISTS,
                    "Another pre-consultation is still in progress.");
        }

        Conversation conversation = Conversation.start(patientId, appointmentId, questionLimit, now);
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-" + conversation.id(), OPENING_MESSAGE,
                now));
        return conversation;
    }
}
