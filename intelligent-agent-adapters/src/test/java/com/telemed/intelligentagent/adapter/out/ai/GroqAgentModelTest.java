package com.telemed.intelligentagent.adapter.out.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.AgentReply;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.SummaryDraft;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.Turn;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.AgentInstructions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Groq's OpenAI-compatible chat completions, emulated by a local server. */
class GroqAgentModelTest {

    private static final String KEY = "test-key-not-real";
    private static final List<Turn> TRANSCRIPT = List.of(
            new Turn(Sender.PATIENT, "Me duele la cabeza"),
            new Turn(Sender.AGENT, "¿Desde cuándo?"),
            new Turn(Sender.PATIENT, "Desde hace dos días"));

    private final ObjectMapper json = new ObjectMapper();
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> contents = new ArrayDeque<>();
    private final Deque<String> errorBodies = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private HttpServer server;
    private GroqAgentModel model;

    @BeforeEach
    void startFakeGroq() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = statuses.isEmpty() ? 200 : statuses.pop()[0];
            String body = status == 200
                    ? json.writeValueAsString(json.createObjectNode().set("choices", json.createArrayNode()
                    .add(json.createObjectNode().set("message",
                            json.createObjectNode().put("content", contents.pop())))))
                    : errorBodies.isEmpty() ? "{\"error\":{\"message\":\"unavailable\"}}" : errorBodies.pop();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
        model = new GroqAgentModel(baseUrl, KEY, "openai/gpt-oss-20b", Duration.ofSeconds(2), 3, Duration.ofMillis(1));
    }

    @AfterEach
    void stopFakeGroq() {
        server.stop(0);
    }

    @Test
    void sendsTheInstructionsAsSystemAndThePatientAsDataUnderAStrictSchema() {
        contents.add("{\"message\":\"¿Qué tan intenso es?\",\"enoughInformation\":false}");

        AgentReply reply = model.nextTurn(TRANSCRIPT);

        assertThat(reply).isEqualTo(new AgentReply("¿Qué tan intenso es?", false));
        JsonNode request = requests.get(0);
        assertThat(request.get("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(request.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(request.at("/messages/0/content").asText()).isEqualTo(AgentInstructions.CONVERSATION);
        assertThat(request.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(request.at("/messages/1/content").asText()).isEqualTo("Me duele la cabeza");
        assertThat(request.at("/messages/2/role").asText()).isEqualTo("assistant");
        assertThat(request.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(request.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(request.get("include_reasoning").asBoolean()).isFalse();
        assertThat(authorizations.get(0)).isEqualTo("Bearer " + KEY);
    }

    @Test
    void summarisesWithNullsForWhatThePatientDidNotSay() {
        contents.add("{\"consultationReason\":\"Dolor de cabeza\",\"evolutionTime\":\"Hace 2 días\","
                + "\"detailedSymptoms\":null,\"relevantHistory\":null}");

        SummaryDraft summary = model.summarize(TRANSCRIPT);

        assertThat(summary).isEqualTo(new SummaryDraft("Dolor de cabeza", "Hace 2 días", null, null));
        assertThat(requests.get(0).at("/messages/0/content").asText()).isEqualTo(AgentInstructions.SUMMARY);
    }

    @Test
    void retriesTransientFailuresWithinItsBudget() {
        statuses.add(new int[] {503});
        statuses.add(new int[] {429});
        contents.add("{\"message\":\"¿Algo más?\",\"enoughInformation\":true}");

        assertThat(model.nextTurn(TRANSCRIPT).enoughInformation()).isTrue();
        assertThat(requests).hasSize(3);
    }

    @Test
    void reportsTheModelUnavailableOnceTheBudgetIsSpent() {
        statuses.add(new int[] {503});
        statuses.add(new int[] {503});
        statuses.add(new int[] {503});

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT))
                .isInstanceOf(AgentModelUnavailableException.class)
                .hasMessageNotContaining(KEY);
        assertThat(requests).hasSize(3);
    }

    @Test
    void retriesWhenTheModelGeneratedOutputGroqCouldNotParse() {
        // Groq answers 400 for these, but they are the model's failure, not a bad request: another try may pass.
        statuses.add(new int[] {400});
        errorBodies.add("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"output_parse_failed\"}}");
        statuses.add(new int[] {400});
        errorBodies.add("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"json_validate_failed\"}}");
        contents.add("{\"message\":\"¿Desde cuándo?\",\"enoughInformation\":false}");

        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("¿Desde cuándo?");
        assertThat(requests).hasSize(3);

        // gpt-oss may also emit a tool call nobody offered it: Groq's tool_use_failed.
        statuses.add(new int[] {400});
        errorBodies.add("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"tool_use_failed\"}}");
        contents.add("{\"message\":\"¿Algo más?\",\"enoughInformation\":false}");
        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("¿Algo más?");
        assertThat(requests).hasSize(5);
    }

    @Test
    void doesNotRetryAClientError() {
        statuses.add(new int[] {400});

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void rejectsARepliesOutsideTheSchema() {
        contents.add("not json at all");

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        assertThatThrownBy(() -> new GroqAgentModel("http://127.0.0.1:1", " ", "m", Duration.ofSeconds(1), 1,
                Duration.ofMillis(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
