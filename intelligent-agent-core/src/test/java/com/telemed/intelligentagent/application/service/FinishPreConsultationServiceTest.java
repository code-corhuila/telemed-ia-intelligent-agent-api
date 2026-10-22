package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.FinalizationReason;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.NOTHING_TO_SUMMARIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinishPreConsultationServiceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");

    private final InMemoryConversations conversations = new InMemoryConversations();
    private final InMemoryMessages messages = new InMemoryMessages();
    private final InMemorySummaries summaries = new InMemorySummaries();
    private final SummarizingModel model = new SummarizingModel();
    private final FinishPreConsultationService service = new FinishPreConsultationService(conversations, messages,
            summaries, new PreConsultationSummarizer(model), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void finishesAndSummarisesFromARedactedTranscript() {
        Conversation conversation = withPatientSaying("Me duele la cabeza, mi cédula es 1075123456");
        model.draft = new AgentModelPort.SummaryDraft("Dolor de cabeza", "Dos días", null, null);

        PreConsultationSummary summary = service.finish(PATIENT, conversation.id());

        assertThat(conversation.finalizationReason()).isEqualTo(FinalizationReason.COMPLETED);
        assertThat(conversations.stored.get(conversation.id()).isActive()).isFalse();
        assertThat(summaries.stored).containsExactly(summary);
        assertThat(summary.consultationReason()).isEqualTo("Dolor de cabeza");
        assertThat(summary.evolutionTime()).isEqualTo("Dos días");
        assertThat(model.lastTranscript.getLast().text()).doesNotContain("1075123456");
    }

    @Test
    void dropsAnySummaryFieldThatCrossesTheClinicalLine() {
        Conversation conversation = withPatientSaying("Me duele la cabeza");
        model.draft = new AgentModelPort.SummaryDraft("Dolor de cabeza", "Dos días",
                "Probablemente tiene migraña", "Te recomiendo tomar acetaminofén");

        PreConsultationSummary summary = service.finish(PATIENT, conversation.id());

        assertThat(summary.detailedSymptoms()).isNull();
        assertThat(summary.relevantHistory()).isNull();
    }

    @Test
    void fallsBackToThePatientsFirstWordsWhenTheModelGivesNoReason() {
        Conversation conversation = withPatientSaying("Tengo tos, escríbeme a ana@correo.com");
        model.draft = new AgentModelPort.SummaryDraft(null, null, null, null);

        PreConsultationSummary summary = service.finish(PATIENT, conversation.id());

        assertThat(summary.consultationReason()).isEqualTo("Tengo tos, escríbeme a [correo]");
    }

    @Test
    void returnsTheSameSummaryWhenFinishedAgain() {
        Conversation conversation = withPatientSaying("Me duele la cabeza");
        PreConsultationSummary first = service.finish(PATIENT, conversation.id());

        assertThat(service.finish(PATIENT, conversation.id())).isEqualTo(first);
        assertThat(model.calls).isEqualTo(1);
        assertThat(summaries.stored).hasSize(1);
    }

    @Test
    void refusesToFinishBeforeThePatientSaidAnything() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 3, NOW);
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-key-01", "¿Qué te trae?", NOW));

        assertThatThrownBy(() -> service.finish(PATIENT, conversation.id()))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(NOTHING_TO_SUMMARIZE);
        assertThat(conversation.isActive()).isTrue();
        assertThat(model.calls).isZero();
    }

    @Test
    void finishesOnlyTheirOwnConversation() {
        Conversation conversation = withPatientSaying("Me duele la cabeza");

        assertThatThrownBy(() -> service.finish(UUID.randomUUID(), conversation.id()))
                .extracting("reason").isEqualTo(CONVERSATION_NOT_FOUND);
        assertThat(conversation.isActive()).isTrue();
        assertThat(summaries.stored).isEmpty();
    }

    private Conversation withPatientSaying(String text) {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 3, NOW);
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-key-01", "¿Qué te trae?", NOW));
        messages.save(Message.of(conversation.id(), Sender.PATIENT, "turn-key-0001", text, NOW));
        return conversation;
    }

    private static final class SummarizingModel implements AgentModelPort {

        SummaryDraft draft = new SummaryDraft("Dolor de cabeza", null, null, null);
        int calls;
        List<Turn> lastTranscript = List.of();

        @Override
        public AgentReply nextTurn(List<Turn> transcript) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SummaryDraft summarize(List<Turn> transcript) {
            calls++;
            lastTranscript = transcript;
            return draft;
        }
    }
}
