package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;

/** Summarises first and only then finishes, so a failed summary leaves the conversation open to retry. */
public class FinishPreConsultationService implements FinishPreConsultationUseCase {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final SummaryRepository summaries;
    private final PreConsultationSummarizer summarizer;
    private final Clock clock;

    public FinishPreConsultationService(ConversationRepository conversations, MessageRepository messages,
                                        SummaryRepository summaries, PreConsultationSummarizer summarizer,
                                        Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.summaries = summaries;
        this.summarizer = summarizer;
        this.clock = clock;
    }

    @Override
    public PreConsultationSummary finish(UUID patientId, UUID conversationId) {
        Conversation conversation = conversations.findById(conversationId)
                .filter(found -> found.belongsTo(patientId))
                .orElseThrow(() -> new AgentDomainException(CONVERSATION_NOT_FOUND, "The conversation was not found."));
        if (!conversation.isActive()) {
            return summaries.findByConversation(conversationId)
                    .orElseThrow(() -> new AgentDomainException(CONVERSATION_FINISHED,
                            "The pre-consultation has already finished."));
        }

        Instant now = clock.instant();
        PreConsultationSummary summary = summarizer.summarize(conversation,
                messages.findByConversation(conversationId), now);
        conversation.finish(now);
        conversations.save(conversation);
        summaries.save(summary);
        return summary;
    }
}
