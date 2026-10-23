package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.SendMessageUseCase.AgentTurn;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.ConversationStatus;
import com.telemed.intelligentagent.domain.model.FinalizationReason;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.ClinicalSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.MESSAGE_TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class SendMessageServiceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final String KEY = "turn-key-0001";

    private final InMemoryConversations conversations = new InMemoryConversations();
    private final InMemoryMessages messages = new InMemoryMessages();
    private final FakeModel model = new FakeModel();
    private final InMemorySummaries summaries = new InMemorySummaries();
    private final TestUnitOfWork unitOfWork = new TestUnitOfWork();
    private final SendMessageService service = new SendMessageService(conversations, messages, summaries,
            new PreConsultationSummarizer(model), model, unitOfWork, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesTheTurnAndTheSummaryInOneUnitOfWorkAfterTheModelAnswers() {
        Conversation conversation = started(3);
        model.next = new AgentModelPort.AgentReply("¿Algo más?", true);
        unitOfWork.defer();

        service.send(PATIENT, conversation.id(), KEY, "Eso es todo");
        assertThat(model.calls).isEqualTo(1);
        assertThat(messages.stored).hasSize(1);
        assertThat(summaries.stored).isEmpty();

        unitOfWork.commit();
        assertThat(unitOfWork.units).isEqualTo(1);
        assertThat(messages.stored).hasSize(3);
        assertThat(summaries.stored).hasSize(1);
    }

    @Test
    void asksTheModelWithARedactedTranscriptAndStoresBothTurns() {
        Conversation conversation = started(3);
        model.next = new AgentModelPort.AgentReply("¿Desde cuándo te duele?", false);

        AgentTurn turn = service.send(PATIENT, conversation.id(), KEY,
                "Me duele la cabeza, escríbeme a ana@correo.com o al 3001234567");

        assertThat(turn.reply().content()).isEqualTo("¿Desde cuándo te duele?");
        assertThat(turn.conversation().questionsAsked()).isEqualTo(1);
        // The patient's own words are stored; only the model sees them redacted.
        assertThat(model.lastTranscript.getLast().text())
                .isEqualTo("Me duele la cabeza, escríbeme a [correo] o al [número]");
        assertThat(messages.findByConversation(conversation.id()))
                .extracting(Message::sender, Message::idempotencyKey)
                .containsExactly(tuple(Sender.AGENT, "opening-key-01"), tuple(Sender.PATIENT, KEY),
                        tuple(Sender.AGENT, KEY));
        assertThat(messages.stored.get(1).content()).contains("ana@correo.com");
        assertThat(messages.stored.get(2).sentAt()).isAfter(messages.stored.get(1).sentAt());
    }

    @Test
    void answersAClinicalRequestWithTheBoundaryWithoutAskingTheModel() {
        Conversation conversation = started(3);

        AgentTurn turn = service.send(PATIENT, conversation.id(), KEY, "¿Qué medicamento puedo tomar?");

        assertThat(turn.reply().content()).isEqualTo(ClinicalSafetyPolicy.BOUNDARY_MESSAGE);
        assertThat(model.calls).isZero();
        assertThat(turn.conversation().questionsAsked()).isZero();
    }

    @Test
    void neverPassesOnAReplyThatCrossesTheClinicalLine() {
        Conversation conversation = started(3);
        model.next = new AgentModelPort.AgentReply("Te recomiendo tomar ibuprofeno 400 mg.", false);

        AgentTurn turn = service.send(PATIENT, conversation.id(), KEY, "Me duele la espalda");

        assertThat(turn.reply().content()).isEqualTo(ClinicalSafetyPolicy.BOUNDARY_MESSAGE);
    }

    @Test
    void closesWhenTheModelHasEnoughInformation() {
        Conversation conversation = started(3);
        model.next = new AgentModelPort.AgentReply("¿Algo más?", true);

        AgentTurn turn = service.send(PATIENT, conversation.id(), KEY, "Eso es todo");

        assertThat(turn.reply().content()).isEqualTo(SendMessageService.CLOSING_MESSAGE);
        assertThat(turn.conversation().status()).isEqualTo(ConversationStatus.FINISHED);
        assertThat(turn.conversation().finalizationReason()).isEqualTo(FinalizationReason.COMPLETED);
        assertThat(summaries.findByConversation(conversation.id())).isPresent();
    }

    @Test
    void closesAfterTheAnswerToTheLastQuestionWithoutAskingTheModel() {
        Conversation conversation = started(1);
        conversation.recordAgentQuestion();

        AgentTurn turn = service.send(PATIENT, conversation.id(), KEY, "Desde ayer");

        assertThat(turn.reply().content()).isEqualTo(SendMessageService.CLOSING_MESSAGE);
        assertThat(turn.conversation().finalizationReason()).isEqualTo(FinalizationReason.QUESTION_LIMIT_REACHED);
        assertThat(summaries.findByConversation(conversation.id()))
                .hasValueSatisfying(summary -> assertThat(summary.consultationReason()).isEqualTo("Dolor"));
        assertThat(model.calls).isZero();
    }

    @Test
    void returnsTheSameReplyWhenTheTurnIsRetried() {
        Conversation conversation = started(3);
        model.next = new AgentModelPort.AgentReply("¿Desde cuándo te duele?", false);
        AgentTurn first = service.send(PATIENT, conversation.id(), KEY, "Me duele la cabeza");

        AgentTurn retried = service.send(PATIENT, conversation.id(), KEY, "Me duele la cabeza");

        assertThat(retried.reply()).isEqualTo(first.reply());
        assertThat(model.calls).isEqualTo(1);
        assertThat(messages.stored).hasSize(3);
        assertThat(retried.conversation().questionsAsked()).isEqualTo(1);
    }

    @Test
    void storesNothingWhenTheModelIsUnavailable() {
        Conversation conversation = started(3);
        model.unavailable = true;

        assertThatThrownBy(() -> service.send(PATIENT, conversation.id(), KEY, "Me duele la cabeza"))
                .isInstanceOf(AgentModelUnavailableException.class);
        assertThat(messages.stored).hasSize(1);
        assertThat(conversations.stored.get(conversation.id()).questionsAsked()).isZero();
    }

    @Test
    void acceptsMessagesOnlyInTheirOwnActiveConversation() {
        Conversation conversation = started(3);
        assertRejected(UUID.randomUUID(), conversation.id(), "Hola", CONVERSATION_NOT_FOUND);
        assertRejected(PATIENT, UUID.randomUUID(), "Hola", CONVERSATION_NOT_FOUND);
        assertRejected(PATIENT, conversation.id(), "x".repeat(1001), MESSAGE_TOO_LONG);

        conversation.finish(NOW);
        assertRejected(PATIENT, conversation.id(), "Hola", CONVERSATION_FINISHED);
        assertThat(model.calls).isZero();
    }

    private Conversation started(int questionLimit) {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), questionLimit, NOW);
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-key-01", "¿Qué te trae?", NOW));
        return conversation;
    }

    private void assertRejected(UUID patient, UUID conversationId, String text, AgentDomainException.Reason reason) {
        int before = messages.stored.size();
        assertThatThrownBy(() -> service.send(patient, conversationId, KEY, text))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(reason);
        assertThat(messages.stored).hasSize(before);
    }

    private static final class FakeModel implements AgentModelPort {

        AgentReply next = new AgentReply("¿Algo más que quieras contar?", false);
        boolean unavailable;
        int calls;
        List<Turn> lastTranscript = new ArrayList<>();

        @Override
        public AgentReply nextTurn(List<Turn> transcript) {
            calls++;
            lastTranscript = transcript;
            if (unavailable) {
                throw new AgentModelUnavailableException("down");
            }
            return next;
        }

        @Override
        public SummaryDraft summarize(List<Turn> transcript) {
            return new SummaryDraft("Dolor", null, null, null);
        }
    }
}
