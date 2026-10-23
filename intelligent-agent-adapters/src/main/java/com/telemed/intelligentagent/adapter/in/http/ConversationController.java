package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.adapter.in.http.ConversationResponses.ConversationResponse;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.domain.model.Conversation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** The patient is always the token's subject; ids in the body never choose whose data is touched. */
@RestController
@RequestMapping(ConversationController.BASE)
public class ConversationController {

    static final String BASE = "/api/v1/agent/conversations";

    private final StartPreConsultationUseCase start;
    private final GetPreConsultationUseCase get;

    public ConversationController(StartPreConsultationUseCase start, GetPreConsultationUseCase get) {
        this.start = start;
        this.get = get;
    }

    public record StartConversationRequest(@NotNull UUID appointmentId) {
    }

    // A retry with the same key gets the same conversation back (idempotency_records).
    @PostMapping
    public ResponseEntity<ConversationResponse> start(@AuthenticationPrincipal Jwt token,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @Valid @RequestBody StartConversationRequest request) {
        UUID patient = TokenSubject.of(token);
        Conversation conversation = start.start(patient, request.appointmentId(), idempotencyKey);
        return ResponseEntity.created(URI.create(BASE + "/" + conversation.id()))
                .body(ConversationResponse.of(get.get(patient, conversation.id())));
    }

    @GetMapping("/{id}")
    public ConversationResponse get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return ConversationResponse.of(get.get(TokenSubject.of(token), id));
    }

    @GetMapping(params = "appointmentId")
    public ConversationResponse getByAppointment(@AuthenticationPrincipal Jwt token,
                                                 @RequestParam UUID appointmentId) {
        return ConversationResponse.of(get.getByAppointment(TokenSubject.of(token), appointmentId));
    }
}
