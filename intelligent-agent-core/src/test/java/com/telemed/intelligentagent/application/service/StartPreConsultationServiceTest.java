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
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.IDEMPOTENCY_KEY_REUSED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_IDEMPOTENCY_KEY;
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
    private final InMemoryIdempotencyRecords idempotency = new InMemoryIdempotencyRecords();
    private final TestUnitOfWork unitOfWork = new TestUnitOfWork();
    private final StartPreConsultationService service = new StartPreConsultationService(
            id -> Optional.ofNullable(appointments.get(id)), conversations, messages, idempotency, unitOfWork, 8,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesTheConversationAndItsOpeningInOneUnitOfWork() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);
        unitOfWork.defer();

        service.start(PATIENT, appointment.id(), freshKey());
        assertThat(conversations.stored).isEmpty();
        assertThat(messages.stored).isEmpty();

        assertThat(idempotency.stored).isEmpty();

        unitOfWork.commit();
        assertThat(unitOfWork.units).isEqualTo(1);
        assertThat(conversations.stored).hasSize(1);
        assertThat(messages.stored).hasSize(1);
        assertThat(idempotency.stored).hasSize(1);
    }

    @Test
    void returnsTheSameConversationWhenTheStartIsRetried() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);
        Conversation first = service.start(PATIENT, appointment.id(), "start-key-0001");
        appointments.clear();

        // The retry is answered from the record, even if the appointment service cannot be asked again.
        Conversation retried = service.start(PATIENT, appointment.id(), "start-key-0001");

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(conversations.stored).hasSize(1);
        assertThat(messages.stored).hasSize(1);
    }

    @Test
    void refusesAKeyAlreadyUsedForAnotherAppointment() {
        service.start(PATIENT, appointment(PATIENT, AppointmentStatus.CONFIRMED).id(), "start-key-0001");

        assertThatThrownBy(() -> service.start(PATIENT, appointment(PATIENT, AppointmentStatus.CONFIRMED).id(),
                "start-key-0001"))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void keysBelongToTheirPatient() {
        Appointment mine = appointment(PATIENT, AppointmentStatus.CONFIRMED);
        UUID other = UUID.randomUUID();
        Appointment theirs = appointment(other, AppointmentStatus.CONFIRMED);
        service.start(other, theirs.id(), "start-key-0001");

        // Another patient's key never replays their conversation.
        assertThat(service.start(PATIENT, mine.id(), "start-key-0001").patientId()).isEqualTo(PATIENT);
    }

    @Test
    void needsAUsableIdempotencyKey() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);

        assertThatThrownBy(() -> service.start(PATIENT, appointment.id(), "short"))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_IDEMPOTENCY_KEY);
        assertThat(conversations.stored).isEmpty();
    }

    @Test
    void startsThePreConsultationOfTheirOwnUpcomingAppointment() {
        Appointment appointment = appointment(PATIENT, AppointmentStatus.CONFIRMED);

        Conversation conversation = service.start(PATIENT, appointment.id(), freshKey());

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
        Conversation first = service.start(PATIENT, appointment.id(), freshKey());
        first.finish(NOW.plusSeconds(60));

        assertRejected(appointment.id(), PRECONSULTATION_ALREADY_EXISTS);
    }

    @Test
    void allowsOnlyOneActivePreConsultationPerPatient() {
        service.start(PATIENT, appointment(PATIENT, AppointmentStatus.CONFIRMED).id(), freshKey());

        assertRejected(appointment(PATIENT, AppointmentStatus.RESCHEDULED).id(), ACTIVE_PRECONSULTATION_EXISTS);
    }

    private static String freshKey() {
        return "start-" + UUID.randomUUID();
    }

    private Appointment appointment(UUID patient, AppointmentStatus status) {
        Appointment appointment = new Appointment(UUID.randomUUID(), patient, UUID.randomUUID(), status,
                NOW.plusSeconds(86_400));
        appointments.put(appointment.id(), appointment);
        return appointment;
    }

    private void assertRejected(UUID appointmentId, AgentDomainException.Reason reason) {
        int before = conversations.stored.size();
        assertThatThrownBy(() -> service.start(PATIENT, appointmentId, freshKey()))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(reason);
        assertThat(conversations.stored).hasSize(before);
    }
}
