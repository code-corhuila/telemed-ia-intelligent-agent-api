package com.telemed.intelligentagent.adapter.out.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.AgentInstructions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Groq's OpenAI-compatible chat completions. The instructions travel as
 * the system message and the patient's words as user messages (data, not
 * orders); answers must match a strict JSON schema and carry no reasoning.
 * Neither the key nor the clinical text is ever logged or put in an error.
 */
public class GroqAgentModel implements AgentModelPort {

    private static final Logger LOG = LoggerFactory.getLogger(GroqAgentModel.class);
    // Groq's 400s for output the model produced badly (unparsable, or a tool call nobody offered it);
    // the request itself was fine, so another try may pass.
    private static final Set<String> UNPARSABLE_GENERATION =
            Set.of("output_parse_failed", "json_validate_failed", "tool_use_failed");
    // gpt-oss reasons first within the same budget (measured up to ~300 tokens on a turn).
    private static final int TURN_MAX_TOKENS = 1024;
    private static final int SUMMARY_MAX_TOKENS = 1536;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int attempts;
    private final Duration backoffBase;

    public GroqAgentModel(String baseUrl, String apiKey, String model, Duration timeout, int attempts,
                          Duration backoffBase) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("The AI provider is enabled but GROQ_API_KEY is not set.");
        }
        this.endpoint = URI.create(baseUrl + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.attempts = Math.max(1, attempts);
        this.backoffBase = backoffBase;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public AgentReply nextTurn(List<Turn> transcript) {
        ObjectNode schema = objectSchema();
        schema.with("properties").set("message", json.createObjectNode().put("type", "string"));
        schema.with("properties").set("enoughInformation", json.createObjectNode().put("type", "boolean"));
        schema.withArray("required").add("message").add("enoughInformation");

        JsonNode reply = complete(AgentInstructions.CONVERSATION, transcript, "agent_turn", schema, TURN_MAX_TOKENS);
        if (!reply.path("message").isTextual() || !reply.path("enoughInformation").isBoolean()) {
            throw new AgentModelUnavailableException("The AI provider answered outside the expected schema.");
        }
        return new AgentReply(reply.get("message").asText(), reply.get("enoughInformation").asBoolean());
    }

    @Override
    public SummaryDraft summarize(List<Turn> transcript) {
        ObjectNode schema = objectSchema();
        for (String field : List.of("consultationReason", "evolutionTime", "detailedSymptoms", "relevantHistory")) {
            schema.with("properties").set(field, json.createObjectNode()
                    .set("type", json.createArrayNode().add("string").add("null")));
            schema.withArray("required").add(field);
        }
        JsonNode summary = complete(AgentInstructions.SUMMARY, transcript, "preconsultation_summary", schema,
                SUMMARY_MAX_TOKENS);
        return new SummaryDraft(text(summary, "consultationReason"), text(summary, "evolutionTime"),
                text(summary, "detailedSymptoms"), text(summary, "relevantHistory"));
    }

    private JsonNode complete(String instructions, List<Turn> transcript, String schemaName, ObjectNode schema,
                              int maxTokens) {
        ObjectNode body = json.createObjectNode()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_completion_tokens", maxTokens)
                .put("include_reasoning", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", instructions);
        transcript.forEach(turn -> messages.addObject()
                .put("role", turn.sender() == Sender.PATIENT ? "user" : "assistant")
                .put("content", turn.text()));
        body.putObject("response_format").put("type", "json_schema").putObject("json_schema")
                .put("name", schemaName).put("strict", true).set("schema", schema);

        String content = send(body).path("choices").path(0).path("message").path("content").asText(null);
        try {
            return json.readTree(content == null ? "" : content);
        } catch (IOException e) {
            throw new AgentModelUnavailableException("The AI provider answered outside the expected schema.");
        }
    }

    /**
     * Bounded retries with full-jitter backoff, only for network errors, 429,
     * 5xx and output the model generated badly (see UNPARSABLE_GENERATION).
     */
    private JsonNode send(ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        for (int attempt = 1; ; attempt++) {
            boolean retryable;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return json.readTree(response.body());
                }
                String errorCode = errorCode(response.body());
                // Status and Groq's error code only: the body may echo the patient's words.
                LOG.warn("AI provider answered {} ({}) on attempt {}", response.statusCode(), errorCode, attempt);
                retryable = response.statusCode() == 429 || response.statusCode() >= 500
                        || UNPARSABLE_GENERATION.contains(errorCode);
            } catch (IOException e) {
                LOG.warn("AI provider unreachable on attempt {}: {}", attempt, e.getClass().getSimpleName());
                retryable = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentModelUnavailableException("The AI provider call was interrupted.");
            }
            if (!retryable || attempt >= attempts) {
                throw new AgentModelUnavailableException("The AI provider is not available right now.");
            }
            pause(attempt);
        }
    }

    private String errorCode(String body) {
        try {
            return json.readTree(body).path("error").path("code").asText("none");
        } catch (IOException | RuntimeException notJson) {
            return "none";
        }
    }

    private void pause(int attempt) {
        long ceiling = backoffBase.toMillis() * (1L << attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentModelUnavailableException("The AI provider call was interrupted.");
        }
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        schema.putArray("required");
        return schema;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
