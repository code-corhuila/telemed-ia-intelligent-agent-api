package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.AppointmentStatus;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.ConversationStatus;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.ACTIVE_PRECONSULTATION_EXISTS;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_ELIGIBLE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.APPOINTMENT_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.PRECONSULTATION_ALREADY_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class StartPreConsultationServiceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    private final Map<UUID, Appointment> appointments = new HashMap<>();
    private final InMemoryConversations conversations = new InMemoryConversations();
    private final InMemoryMessages messages = new InMemoryMessages();
    private final TestUnitOfWork unitOfWork = new TestUnitOfWork();
    private final StartPreConsultationService service = new StartPreConsultationService(
            id -> Optional.ofNullable(appointments.get(id)), conversations, messages, unitOfWork, 8,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesTheConversationAndItsOpeningInOneUnitOfWork() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);
        unitOfWork.defer();

        service.start(PATIENT, appointment.id());
        assertThat(conversations.stored).isEmpty();
        assertThat(messages.stored).isEmpty();

        unitOfWork.commit();
        assertThat(unitOfWork.units).isEqualTo(1);
        assertThat(conversations.stored).hasSize(1);
        assertThat(messages.stored).hasSize(1);
    }

    @Test
    void startsThePreConsultationOfTheirOwnUpcomingAppointment() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);

        Conversation conversation = service.start(PATIENT, appointment.id());

        assertThat(conversation.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(conversation.patientId()).isEqualTo(PATIENT);
        assertThat(conversation.appointmentId()).isEqualTo(appointment.id());
        assertThat(conversation.questionLimit()).isEqualTo(8);
        assertThat(conversations.stored).containsValue(conversation);
        // The agent opens with a fixed greeting: no model call before the patient says anything.
        assertThat(messages.findByConversation(conversation.id()))
                .extracting(Message::sender, Message::content, Message::idempotencyKey)
                .containsExactly(tuple(Sender.AGENT, StartPreConsultationService.OPENING_MESSAGE,
                        "opening-" + conversation.id()));
    }

    @Test
    void treatsAnotherPatientsAppointmentAsOneThatDoesNotExist() {
        Appointment foreign = appointment(UUID.randomUUID(), AppointmentStatus.CONFIRMED);

        assertRejected(foreign.id(), APPOINTMENT_NOT_FOUND);
        assertRejected(UUID.randomUUID(), APPOINTMENT_NOT_FOUND);
    }

    @Test
    void refusesAppointmentsThatAreNoLongerToHappen() {
        assertRejected(appointment(PATIENT, AppointmentStatus.CANCELLED).id(), APPOINTMENT_NOT_ELIGIBLE);
        assertRejected(appointment(PATIENT, AppointmentStatus.COMPLETED).id(), APPOINTMENT_NOT_ELIGIBLE);
    }

    @Test
    void allowsOnlyOnePreConsultationPerAppointment() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);
        Conversation first = service.start(PATIENT, appointment.id());
        first.finish(NOW.plusSeconds(60));

        assertRejected(appointment.id(), PRECONSULTATION_ALREADY_EXISTS);
    }

    @Test
    void allowsOnlyOneActivePreConsultationPerPatient() {
        service.start(PATIENT, appointment(PATIENT, AppointmentStatus.CONFIRMED).id());

        assertRejected(appointment(PATIENT, AppointmentStatus.RESCHEDULED).id(), ACTIVE_PRECONSULTATION_EXISTS);
    }

    private Appointment appointment(UUID patient, AppointmentStatus status) {
        Appointment appointment = new Appointment(UUID.randomUUID(), patient, status, NOW.plusSeconds(86_400));
        appointments.put(appointment.id(), appointment);
        return appointment;
    }

    private void assertRejected(UUID appointmentId, AgentDomainException.Reason reason) {
        int before = conversations.stored.size();
        assertThatThrownBy(() -> service.start(PATIENT, appointmentId))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(reason);
        assertThat(conversations.stored).hasSize(before);
    }
}
