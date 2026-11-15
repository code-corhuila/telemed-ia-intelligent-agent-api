package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase.PreConsultationView;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase.AgentTurn;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.telemed.intelligentagent.TestIdentityKeys.IDENTITY;
import static com.telemed.intelligentagent.TestIdentityKeys.ISSUER;
import static com.telemed.intelligentagent.TestIdentityKeys.token;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.CONVERSATION_FINISHED;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.MESSAGE_TOO_LONG;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.NOTHING_TO_SUMMARIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Sending a message and finishing, over HTTP, as the contract describes them. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class MessageApiTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123Z");
    private static final String KEY = "turn-key-0001";

    private final Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, NOW);

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private SendMessageUseCase send;

    @MockitoBean
    private FinishPreConsultationUseCase finish;

    @MockitoBean
    private GetPreConsultationUseCase get;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @Test
    void answersWithTheAgentsReplyAndWhetherTheConversationGoesOn() {
        Message reply = Message.of(conversation.id(), Sender.AGENT, KEY, "¿Desde cuándo te duele?", NOW);
        when(send.send(PATIENT, conversation.id(), KEY, "Me duele la cabeza"))
                .thenReturn(new AgentTurn(reply, conversation));

        ResponseEntity<String> response = post(messagesPath(), KEY, "{\"content\":\"Me duele la cabeza\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"id\":\"" + reply.id() + "\"", "\"sender\":\"AGENT\"")
                .contains("\"content\":\"¿Desde cuándo te duele?\"", "\"sentAt\":\"2026-10-22T15:00:00.123Z\"")
                .contains("\"conversationStatus\":\"ACTIVE\"")
                .doesNotContain(KEY);
    }

    @Test
    void needsAKeyAndContentToSend() {
        ResponseEntity<String> noKey = post(messagesPath(), null, "{\"content\":\"Hola\"}");
        ResponseEntity<String> noContent = post(messagesPath(), KEY, "{}");

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getBody()).contains("\"field\":\"Idempotency-Key\"");
        assertThat(noContent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noContent.getBody()).contains("\"field\":\"content\"");
        verifyNoInteractions(send);
    }

    @Test
    void reportsTheTurnRulesWithTheCommonEnvelope() {
        assertSendFails(new AgentDomainException(MESSAGE_TOO_LONG, "x"), HttpStatus.BAD_REQUEST, "MESSAGE_TOO_LONG");
        assertSendFails(new AgentDomainException(CONVERSATION_FINISHED, "x"), HttpStatus.CONFLICT,
                "CONVERSATION_FINISHED");
        assertSendFails(new AgentModelUnavailableException("provider down"), HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE");
    }

    @Test
    void finishesWithoutHandingTheSummaryToThePatient() {
        conversation.finish(NOW.plusSeconds(60));
        PreConsultationSummary summary = PreConsultationSummary.of(conversation, "Dolor de cabeza", "Dos días",
                null, null, NOW.plusSeconds(60));
        when(finish.finish(PATIENT, conversation.id())).thenReturn(summary);
        when(get.get(PATIENT, conversation.id()))
                .thenReturn(new PreConsultationView(conversation, List.of()));

        ResponseEntity<String> response = post(finishPath(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .startsWith("{\"conversation\":{\"id\":\"" + conversation.id() + "\"")
                .contains("\"status\":\"FINISHED\"")
                // The summary goes to the professional only.
                .doesNotContain("preconsultationSummary", "Dolor de cabeza", "Dos días");
    }

    @Test
    void refusesToFinishWithNothingToSummarise() {
        doThrow(new AgentDomainException(NOTHING_TO_SUMMARIZE, "x")).when(finish).finish(any(), any());

        ResponseEntity<String> response = post(finishPath(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"error\":\"NOTHING_TO_SUMMARIZE\"");
    }

    private void assertSendFails(RuntimeException failure, HttpStatus status, String error) {
        doThrow(failure).when(send).send(any(), any(), any(), any());

        ResponseEntity<String> response = post(messagesPath(), KEY, "{\"content\":\"Hola\"}");

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"error\":\"" + error + "\"").doesNotContain("provider down");
    }

    private String messagesPath() {
        return "/api/v1/agent/conversations/" + conversation.id() + "/messages";
    }

    private String finishPath() {
        return "/api/v1/agent/conversations/" + conversation.id() + "/finish";
    }

    private ResponseEntity<String> post(String path, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(IDENTITY, ISSUER, PATIENT.toString(), "PATIENT", Instant.now().plusSeconds(600)));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
