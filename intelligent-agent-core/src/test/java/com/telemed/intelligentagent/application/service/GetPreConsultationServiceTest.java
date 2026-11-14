package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase.PreConsultationView;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetPreConsultationServiceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    private final InMemoryConversations conversations = new InMemoryConversations();
    private final InMemoryMessages messages = new InMemoryMessages();
    private final GetPreConsultationService service =
            new GetPreConsultationService(conversations, messages);

    @Test
    void showsAnActiveConversationWithItsMessagesAndNoSummaryYet() {
        Conversation conversation = conversationOf(PATIENT);

        PreConsultationView view = service.get(PATIENT, conversation.id());

        assertThat(view.conversation()).isEqualTo(conversation);
        assertThat(view.messages()).extracting(Message::content).containsExactly("¿Qué te trae?");
    }

    @Test
    void neverHandsTheSummaryToThePatient() {
        Conversation conversation = conversationOf(PATIENT);
        conversation.finish(NOW);

        // The summary goes to the professional only: the patient's view has no place for it.
        assertThat(service.get(PATIENT, conversation.id()).conversation().isActive()).isFalse();
        assertThat(PreConsultationView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("conversation", "messages");
    }

    @Test
    void findsThePreConsultationOfAnAppointment() {
        Conversation conversation = conversationOf(PATIENT);

        assertThat(service.getByAppointment(PATIENT, conversation.appointmentId()).conversation())
                .isEqualTo(conversation);
    }

    @Test
    void neverShowsAnotherPatientsPreConsultation() {
        Conversation foreign = conversationOf(UUID.randomUUID());

        assertThatThrownBy(() -> service.get(PATIENT, foreign.id()))
                .extracting("reason").isEqualTo(CONVERSATION_NOT_FOUND);
        assertThatThrownBy(() -> service.getByAppointment(PATIENT, foreign.appointmentId()))
                .extracting("reason").isEqualTo(CONVERSATION_NOT_FOUND);
        assertThatThrownBy(() -> service.get(PATIENT, UUID.randomUUID()))
                .extracting("reason").isEqualTo(CONVERSATION_NOT_FOUND);
    }

    private Conversation conversationOf(UUID patient) {
        Conversation conversation = Conversation.start(patient, UUID.randomUUID(), 3, NOW);
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-key-01", "¿Qué te trae?", NOW));
        return conversation;
    }
}
