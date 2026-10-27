package com.telemed.intelligentagent.adapter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Week 11 log contract (CP2) in tests: what one class logged, read as events with their fields. */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLogs(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    public static CapturedLogs of(Class<?> type) {
        return new CapturedLogs(type);
    }

    /** Fields of every line whose "event" is the given name, plus its level and message. */
    public List<Map<String, Object>> events(String name) {
        return appender.list.stream()
                .map(CapturedLogs::fields)
                .filter(fields -> Objects.equals(name, fields.get("event")))
                .toList();
    }

    /** Everything written, to check that no clinical text slipped into a line. */
    public String everything() {
        return appender.list.stream().map(event -> fields(event).toString()).reduce("", String::concat);
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                fields.put(pair.key, pair.value);
            }
        }
        fields.put("level", event.getLevel().toString());
        fields.put("message", event.getFormattedMessage());
        return fields;
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
