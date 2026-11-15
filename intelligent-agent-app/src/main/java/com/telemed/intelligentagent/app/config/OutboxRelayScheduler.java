package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.application.port.in.RelayOutboxEventsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the relay with a fixed pause; a failed run is logged and the next one retries. */
public class OutboxRelayScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final RelayOutboxEventsUseCase relay;

    public OutboxRelayScheduler(RelayOutboxEventsUseCase relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${agent.outbox-relay.interval}")
    public void relayPending() {
        try {
            relay.relayPending();
        } catch (RuntimeException failure) {
            // Type only: the events stay pending, and agent-db or broker details stay out of the log.
            LOG.atWarn()
                    .addKeyValue("event", "outbox.relay.failed")
                    .addKeyValue("error_type", failure.getClass().getName())
                    .log("outbox relay run failed; the events stay pending");
        }
    }
}
