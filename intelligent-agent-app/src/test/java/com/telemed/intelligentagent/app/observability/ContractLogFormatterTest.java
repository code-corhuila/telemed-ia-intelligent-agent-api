package com.telemed.intelligentagent.app.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Week 11 log contract (CP2, 02-log-contract.md): one JSON object per line, with the mandatory fields. */
class ContractLogFormatterTest {

    private final ContractLogFormatter formatter = new ContractLogFormatter("0.1.0", "dev");

    @Test
    void writesEveryMandatoryFieldOfTheContract() throws Exception {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setTimeStamp(1_792_768_801_120L);
        event.setMessage("summary stored");
        event.setMDCPropertyMap(Map.of("trace_id", "7d3f9a2c4b1e8f60a9c2d5e7f1b3a4c6",
                "span_id", "2b3c4d5e6f708192", "correlation_id", "corr-0001"));
        event.setKeyValuePairs(List.of(new KeyValuePair("event", "preconsultation.summary.generated"),
                new KeyValuePair("duration_ms", 412)));

        String line = formatter.format(event);
        JsonNode json = new ObjectMapper().readTree(line);

        assertThat(line).endsWith("\n");
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-10-23T15:20:01.120Z");
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("service").asText()).isEqualTo("intelligent-agent-api");
        assertThat(json.get("version").asText()).isEqualTo("0.1.0");
        assertThat(json.get("environment").asText()).isEqualTo("dev");
        assertThat(json.get("trace_id").asText()).isEqualTo("7d3f9a2c4b1e8f60a9c2d5e7f1b3a4c6");
        assertThat(json.get("span_id").asText()).isEqualTo("2b3c4d5e6f708192");
        assertThat(json.get("correlation_id").asText()).isEqualTo("corr-0001");
        assertThat(json.get("event").asText()).isEqualTo("preconsultation.summary.generated");
        assertThat(json.get("message").asText()).isEqualTo("summary stored");
        assertThat(json.get("duration_ms").asLong()).isEqualTo(412);
    }

    @Test
    void namesPlainLogLinesAfterTheirLogger() throws Exception {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.WARN);
        event.setLoggerName("com.telemed.intelligentagent.adapter.out.ai.GroqAgentModel");
        event.setMessage("AI provider answered 429");
        event.setMDCPropertyMap(Map.of());

        JsonNode json = new ObjectMapper().readTree(formatter.format(event));

        assertThat(json.get("event").asText()).isEqualTo("log.GroqAgentModel");
        assertThat(json.has("trace_id")).isTrue();
    }
}
