package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.adapter.in.http.ConversationResponses.ConversationResponse;
import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase.AgentTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The patient's turns in their own conversation: a message, or finishing it. */
@RestController
@RequestMapping(ConversationController.BASE + "/{id}")
public class TurnController {

    private static final Logger LOG = LoggerFactory.getLogger(TurnController.class);

    private final SendMessageUseCase send;
    private final FinishPreConsultationUseCase finish;
    private final GetPreConsultationUseCase get;

    public TurnController(SendMessageUseCase send, FinishPreConsultationUseCase finish,
                          GetPreConsultationUseCase get) {
        this.send = send;
        this.finish = finish;
        this.get = get;
    }

    public record SendMessageRequest(@NotNull String content) {
    }

    /** The contract's Message, plus whether the conversation goes on, so the portal knows when to stop. */
    public record ReplyResponse(UUID id, String sender, String content, Instant sentAt, String conversationStatus) {
    }

    /** The summary is generated and stored for the professional; the patient gets the finished conversation. */
    public record FinishResponse(ConversationResponse conversation) {
    }

    // A retry with the same key gets the same reply without asking the model again.
    @PostMapping("/messages")
    public ReplyResponse send(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody SendMessageRequest request) {
        AgentTurn turn = send.send(TokenSubject.of(token), id, idempotencyKey, request.content());
        // The patient's words are clinical data: only their length is logged.
        LOG.atInfo()
                .addKeyValue("event", "conversation.message.answered")
                .addKeyValue("conversation_id", id.toString())
                .addKeyValue("message_chars", request.content().length())
                .addKeyValue("turn", turn.conversation().questionsAsked())
                .addKeyValue("conversation_status", turn.conversation().status().name())
                .log("agent answered the patient's message");
        return new ReplyResponse(turn.reply().id(), turn.reply().sender().name(), turn.reply().content(),
                turn.reply().sentAt(), turn.conversation().status().name());
    }

    @PostMapping("/finish")
    public FinishResponse finish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        UUID patient = TokenSubject.of(token);
        LOG.atInfo()
                .addKeyValue("event", "conversation.finish.received")
                .addKeyValue("conversation_id", id.toString())
                .log("finish request accepted");
        finish.finish(patient, id);
        return new FinishResponse(ConversationResponse.of(get.get(patient, id)));
    }
}
