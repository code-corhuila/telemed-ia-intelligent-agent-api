package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.AgentReply;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.Turn;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.ClinicalSafetyPolicy;
import com.telemed.intelligentagent.domain.safety.PatientMessagePolicy;
import com.telemed.intelligentagent.domain.safety.PersonalDataRedactor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_NOT_FOUND;

/**
 * One patient turn, guarded on both sides of the model: the patient's text is
 * cleaned, clinical requests never reach the model, the model only sees a
 * redacted transcript, and its reply goes through ClinicalSafetyPolicy.
 * Nothing is stored until the model has answered.
 */
public class SendMessageService implements SendMessageUseCase {

    public static final String CLOSING_MESSAGE = "Gracias, ya tengo la información para tu médico. "
            + "La preconsulta terminó y el resumen quedará listo para tu cita.";

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final SummaryRepository summaries;
    private final PreConsultationSummarizer summarizer;
    private final AgentModelPort model;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PatientMessagePolicy messagePolicy = new PatientMessagePolicy();
    private final ClinicalSafetyPolicy safetyPolicy = new ClinicalSafetyPolicy();
    private final PersonalDataRedactor redactor = new PersonalDataRedactor();

    public SendMessageService(ConversationRepository conversations, MessageRepository messages,
                              SummaryRepository summaries, PreConsultationSummarizer summarizer,
                              AgentModelPort model, UnitOfWork unitOfWork, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.summaries = summaries;
        this.summarizer = summarizer;
        this.model = model;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    @Override
    public AgentTurn send(UUID patientId, UUID conversationId, String idempotencyKey, String text) {
        // Another patient's conversation is reported exactly like a missing one.
        Conversation conversation = conversations.findById(conversationId)
                .filter(found -> found.belongsTo(patientId))
                .orElseThrow(() -> new AgentDomainException(CONVERSATION_NOT_FOUND, "The conversation was not found."));
        List<Message> history = messages.findByConversation(conversationId);
        Optional<Message> replayed = replyTo(history, idempotencyKey);
        if (replayed.isPresent()) {
            return new AgentTurn(replayed.get(), conversation);
        }
        conversation.ensureActive();

        Instant now = clock.instant();
        Message patientMessage = Message.of(conversationId, Sender.PATIENT, idempotencyKey,
                messagePolicy.clean(text), now);
        String reply = nextReply(conversation, history, patientMessage, now);
        Instant repliedAt = clock.instant();
        Message agentMessage = Message.of(conversationId, Sender.AGENT, idempotencyKey, reply,
                repliedAt.isAfter(now) ? repliedAt : now.plusMillis(1));
        // A closed conversation is summarised before anything is stored, so a failure leaves nothing half done.
        Optional<PreConsultationSummary> summary = conversation.isActive()
                ? Optional.empty()
                : Optional.of(summarizer.summarize(conversation, withTurn(history, patientMessage), now));

        unitOfWork.execute(() -> {
            messages.save(patientMessage);
            messages.save(agentMessage);
            conversations.save(conversation);
            summary.ifPresent(summaries::save);
        });
        return new AgentTurn(agentMessage, conversation);
    }

    private String nextReply(Conversation conversation, List<Message> history, Message patientMessage,
                             Instant now) {
        if (safetyPolicy.asksForClinicalDecision(patientMessage.content())) {
            return ClinicalSafetyPolicy.BOUNDARY_MESSAGE;
        }
        if (conversation.questionLimitReached()) {
            conversation.closeAtQuestionLimit(now);
            return CLOSING_MESSAGE;
        }
        List<Turn> transcript = withTurn(history, patientMessage).stream().map(this::redacted).toList();

        AgentReply reply = model.nextTurn(transcript);
        if (reply.enoughInformation()) {
            conversation.finish(now);
            return CLOSING_MESSAGE;
        }
        conversation.recordAgentQuestion();
        return safetyPolicy.safeReply(reply.message());
    }

    private static List<Message> withTurn(List<Message> history, Message patientMessage) {
        List<Message> transcript = new ArrayList<>(history);
        transcript.add(patientMessage);
        return transcript;
    }

    private Turn redacted(Message message) {
        return new Turn(message.sender(), redactor.redact(message.content()));
    }

    private static Optional<Message> replyTo(List<Message> history, String idempotencyKey) {
        boolean answered = history.stream()
                .anyMatch(m -> m.sender() == Sender.PATIENT && m.idempotencyKey().equals(idempotencyKey));
        return answered
                ? history.stream()
                        .filter(m -> m.sender() == Sender.AGENT && m.idempotencyKey().equals(idempotencyKey))
                        .findFirst()
                : Optional.empty();
    }
}
