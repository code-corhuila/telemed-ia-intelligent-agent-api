package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.adapter.CapturedLogs;
import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase.PreConsultationView;
import com.telemed.intelligentagent.application.port.in.PreConsultationsPausedException;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase.AgentTurn;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Week 11 log contract (CP2): the pre-consultation milestones are events with a stable name
 * and ids only; the patient's words are never logged, only their length.
 */
class LogEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");
    private static final UUID PATIENT = UUID.randomUUID();
    private static final String PATIENT_WORDS = "Me duele la cabeza desde ayer";

    private final Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW);
    private final Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(PATIENT.toString()).build();
    private final StartPreConsultationUseCase start = mock(StartPreConsultationUseCase.class);
    private final GetPreConsultationUseCase get = mock(GetPreConsultationUseCase.class);
    private final SendMessageUseCase send = mock(SendMessageUseCase.class);
    private final FinishPreConsultationUseCase finish = mock(FinishPreConsultationUseCase.class);
    private final CapturedLogs conversationLogs = CapturedLogs.of(ConversationController.class);
    private final CapturedLogs turnLogs = CapturedLogs.of(TurnController.class);
    private final CapturedLogs errorLogs = CapturedLogs.of(ApiExceptionHandler.class);

    @AfterEach
    void detach() {
        conversationLogs.close();
        turnLogs.close();
        errorLogs.close();
    }

    @Test
    void logsTheStartOfAConversation() {
        when(start.start(eq(PATIENT), any(), anyString())).thenReturn(conversation);
        when(get.get(PATIENT, conversation.id())).thenReturn(new PreConsultationView(conversation, List.of()));

        new ConversationController(start, get).start(token, "start-key-0001",
                new ConversationController.StartConversationRequest(conversation.appointmentId()));

        assertThat(conversationLogs.events("conversation.started")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("level", "INFO")
                .containsEntry("conversation_id", conversation.id().toString())
                .containsEntry("conversation_status", "ACTIVE"));
    }

    @Test
    void logsAMessageTurnWithTheLengthOfThePatientsWordsOnly() {
        conversation.recordAgentQuestion();
        Message reply = Message.of(conversation.id(), Sender.AGENT, "msg-key-0001", "¿Desde cuándo?", NOW);
        when(send.send(PATIENT, conversation.id(), "msg-key-0001", PATIENT_WORDS))
                .thenReturn(new AgentTurn(reply, conversation));

        turnController().send(token, conversation.id(), "msg-key-0001",
                new TurnController.SendMessageRequest(PATIENT_WORDS));

        assertThat(turnLogs.events("conversation.message.answered")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("conversation_id", conversation.id().toString())
                .containsEntry("message_chars", PATIENT_WORDS.length())
                .containsEntry("turn", 1)
                .containsEntry("conversation_status", "ACTIVE"));
        assertThat(turnLogs.everything()).doesNotContain(PATIENT_WORDS).doesNotContain("¿Desde cuándo?");
    }

    @Test
    void logsTheFinishRequest() {
        when(get.get(PATIENT, conversation.id())).thenReturn(new PreConsultationView(conversation, List.of()));

        turnController().finish(token, conversation.id());

        assertThat(turnLogs.events("conversation.finish.received")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("conversation_id", conversation.id().toString()));
    }

    @Test
    void logsEachRejectionWithTheCodeOfTheErrorEnvelope() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.domainRule(new AgentDomainException(CONVERSATION_FINISHED, "x"), request);
        handler.unavailable(new AgentModelUnavailableException("x"), request);
        handler.internalError(new IllegalStateException("boom"), request);

        assertThat(errorLogs.events("request.rejected")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("level", "WARN").containsEntry("error_code", "CONVERSATION_FINISHED"));
        assertThat(errorLogs.events("dependency.unavailable")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("level", "WARN").containsEntry("error_code", "SERVICE_UNAVAILABLE"));
        assertThat(errorLogs.events("request.failed")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("level", "ERROR").containsEntry("error_code", "INTERNAL_ERROR"));
    }

    @Test
    void answersAPausedStartWithItsOwnCodeSoThePortalCanSkipThePreConsultation() {
        var response = new ApiExceptionHandler().paused(new PreConsultationsPausedException(),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().error()).isEqualTo("PRECONSULTATION_PAUSED");
        assertThat(response.getBody().message()).contains("without the pre-consultation");
        assertThat(errorLogs.events("preconsultation.paused")).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("level", "WARN").containsEntry("error_code", "PRECONSULTATION_PAUSED"));
    }

    private TurnController turnController() {
        return new TurnController(send, finish, get);
    }
}
