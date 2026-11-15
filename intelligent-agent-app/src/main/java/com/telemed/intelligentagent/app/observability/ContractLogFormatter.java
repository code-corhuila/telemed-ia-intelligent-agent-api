package com.telemed.intelligentagent.app.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Week 11 log contract (CP2, 02-log-contract.md): one JSON object per line with timestamp,
 * level, service, version, environment, trace_id, span_id, correlation_id, event, message
 * and, on end events, duration_ms. Domain fields come as SLF4J key-value pairs.
 * What must never be logged (clinical text, tokens, keys) is kept out at the call sites.
 */
public class ContractLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final String SERVICE = "intelligent-agent-api";

    private final ObjectMapper json = new ObjectMapper();
    private final String version;
    private final String environment;

    /** Used by Spring Boot (logging.structured.format.console). */
    public ContractLogFormatter(Environment environment) {
        this(environment.getProperty("SERVICE_VERSION", "0.1.0"), environment.getProperty("ENVIRONMENT", "dev"));
    }

    public ContractLogFormatter(String version, String environment) {
        this.version = version;
        this.environment = environment;
    }

    @Override
    public String format(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("level", event.getLevel().toString());
        line.put("service", SERVICE);
        line.put("version", version);
        line.put("environment", environment);
        line.put("trace_id", mdc.get("trace_id"));
        line.put("span_id", mdc.get("span_id"));
        line.put("correlation_id", mdc.get("correlation_id"));
        line.put("event", "log." + simpleName(event.getLoggerName()));
        line.put("message", event.getFormattedMessage());
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                line.put(pair.key, pair.value);
            }
        }
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            line.put("error_type", error.getClassName());
            line.put("stack_trace", ThrowableProxyUtil.asString(error));
        }
        try {
            return json.writeValueAsString(line) + "\n";
        } catch (JsonProcessingException unwritable) {
            return "{\"level\":\"ERROR\",\"service\":\"" + SERVICE + "\",\"message\":\"unwritable log line\"}\n";
        }
    }

    private static String simpleName(String loggerName) {
        if (loggerName == null) {
            return "app";
        }
        return loggerName.substring(loggerName.lastIndexOf('.') + 1);
    }
}
