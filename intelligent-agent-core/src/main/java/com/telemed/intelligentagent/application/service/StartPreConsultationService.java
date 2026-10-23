package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.IdempotencyRecords;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.IdempotencyKeys;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.application.port.out.IdempotencyRecords.Operation.START_PRECONSULTATION;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_ELIGIBLE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.IDEMPOTENCY_KEY_REUSED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_IDEMPOTENCY_KEY;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.PRECONSULTATION_ALREADY_EXISTS;

/** One pre-consultation per appointment, none without one, and only for the appointment's own patient. */
public class StartPreConsultationService implements StartPreConsultationUseCase {

    public static final String OPENING_MESSAGE = "Hola, soy el asistente de preconsulta. Voy a hacerte unas "
            + "preguntas cortas para que tu médico llegue preparado a la cita. ¿Cuál es el motivo de tu consulta?";

    private final AppointmentsPort appointments;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final IdempotencyRecords idempotency;
    private final UnitOfWork unitOfWork;
    private final int questionLimit;
    private final Clock clock;

    public StartPreConsultationService(AppointmentsPort appointments, ConversationRepository conversations,
                                       MessageRepository messages, IdempotencyRecords idempotency,
                                       UnitOfWork unitOfWork, int questionLimit, Clock clock) {
        this.appointments = appointments;
        this.conversations = conversations;
        this.messages = messages;
        this.idempotency = idempotency;
        this.unitOfWork = unitOfWork;
        this.questionLimit = questionLimit;
        this.clock = clock;
    }

    @Override
    public Conversation start(UUID patientId, UUID appointmentId, String idempotencyKey) {
        if (!IdempotencyKeys.isValid(idempotencyKey)) {
            throw new AgentDomainException(INVALID_IDEMPOTENCY_KEY,
                    "The Idempotency-Key must have 8 to 128 printable characters.");
        }
        // A retry is answered from the record, before asking the appointment service again.
        Optional<Conversation> replayed = idempotency.find(patientId, START_PRECONSULTATION, idempotencyKey)
                .flatMap(conversations::findById);
        if (replayed.isPresent()) {
            if (!replayed.get().appointmentId().equals(appointmentId)) {
                throw new AgentDomainException(IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for another appointment.");
            }
            return replayed.get();
        }

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
        Message opening = Message.of(conversation.id(), Sender.AGENT, "opening-" + conversation.id(),
                OPENING_MESSAGE, now);
        unitOfWork.execute(() -> {
            conversations.save(conversation);
            messages.save(opening);
            idempotency.save(patientId, START_PRECONSULTATION, idempotencyKey, conversation.id(), now);
        });
        return conversation;
    }
}
