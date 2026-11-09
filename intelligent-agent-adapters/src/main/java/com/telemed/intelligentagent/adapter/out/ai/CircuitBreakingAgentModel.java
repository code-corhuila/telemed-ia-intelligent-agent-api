package com.telemed.intelligentagent.adapter.out.ai;

import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runbook mitigation 3: after {@code failureThreshold} provider failures in a row the agent
 * answers 503 at once for {@code openFor} instead of making every patient wait out the
 * provider's timeouts and retries. Then one trial call decides whether to close or reopen.
 * Only {@link AgentModelUnavailableException} counts; a bug is not a provider outage.
 */
public class CircuitBreakingAgentModel implements AgentModelPort {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakingAgentModel.class);

    private final AgentModelPort provider;
    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil;
    private boolean trialInFlight;

    public CircuitBreakingAgentModel(AgentModelPort provider, int failureThreshold, Duration openFor, Clock clock) {
        this.provider = provider;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openFor = openFor;
        this.clock = clock;
    }

    @Override
    public AgentReply nextTurn(List<Turn> transcript) {
        return call(() -> provider.nextTurn(transcript));
    }

    @Override
    public SummaryDraft summarize(List<Turn> transcript) {
        return call(() -> provider.summarize(transcript));
    }

    // The provider is called outside the lock, so a slow call never blocks the others.
    private <T> T call(Supplier<T> action) {
        admit();
        T result;
        try {
            result = action.get();
        } catch (AgentModelUnavailableException e) {
            failed();
            throw e;
        } catch (RuntimeException e) {
            released();
            throw e;
        }
        succeeded();
        return result;
    }

    private synchronized void admit() {
        if (openUntil == null) {
            return;
        }
        if (trialInFlight || clock.instant().isBefore(openUntil)) {
            throw new AgentModelUnavailableException("The AI provider is paused after repeated failures.");
        }
        trialInFlight = true;
    }

    private synchronized void succeeded() {
        if (openUntil != null) {
            LOG.atInfo()
                    .addKeyValue("event", "agent.model.circuit.closed")
                    .log("AI provider answered again; circuit closed");
        }
        consecutiveFailures = 0;
        openUntil = null;
        trialInFlight = false;
    }

    private synchronized void failed() {
        consecutiveFailures++;
        if (openUntil != null || consecutiveFailures >= failureThreshold) {
            open();
        }
        trialInFlight = false;
    }

    private synchronized void released() {
        trialInFlight = false;
    }

    private void open() {
        openUntil = clock.instant().plus(openFor);
        LOG.atWarn()
                .addKeyValue("event", "agent.model.circuit.opened")
                .addKeyValue("consecutive_failures", consecutiveFailures)
                .addKeyValue("open_for_ms", openFor.toMillis())
                .log("AI provider keeps failing; answering 503 without calling it");
    }
}
