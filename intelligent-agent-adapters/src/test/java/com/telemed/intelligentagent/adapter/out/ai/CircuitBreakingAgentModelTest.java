package com.telemed.intelligentagent.adapter.out.ai;

import com.telemed.intelligentagent.adapter.CapturedLogs;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runbook mitigation 3: after repeated provider failures the agent stops waiting on Groq for a while. */
class CircuitBreakingAgentModelTest {

    private static final List<AgentModelPort.Turn> TRANSCRIPT = List.of();
    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final FakeModel provider = new FakeModel();
    private final CircuitBreakingAgentModel model = new CircuitBreakingAgentModel(provider, 3, OPEN_FOR, clock);
    private final CapturedLogs logs = CapturedLogs.of(CircuitBreakingAgentModel.class);

    @AfterEach
    void detach() {
        logs.close();
    }

    @Test
    void passesAnswersThroughWhileTheProviderWorks() {
        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("next question");
        assertThat(model.summarize(TRANSCRIPT).consultationReason()).isEqualTo("headache");
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void opensAfterConsecutiveFailuresAndStopsCallingTheProvider() {
        provider.failing = true;
        failThreeTimes();

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThatThrownBy(() -> model.summarize(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThat(provider.calls).isEqualTo(3);
        assertThat(logs.events("agent.model.circuit.opened")).singleElement()
                .satisfies(line -> {
                    assertThat(line).containsEntry("level", "WARN");
                    assertThat(line).containsEntry("consecutive_failures", 3);
                    assertThat(line).containsEntry("open_for_ms", 30_000L);
                });
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        provider.failing = true;
        failTwice();
        provider.failing = false;
        model.nextTurn(TRANSCRIPT);
        provider.failing = true;
        failTwice();

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThat(provider.calls).isEqualTo(6);
        assertThat(logs.events("agent.model.circuit.opened")).isEmpty();
    }

    @Test
    void triesTheProviderAgainAfterTheCooldownAndClosesOnSuccess() {
        provider.failing = true;
        failThreeTimes();
        clock.advance(OPEN_FOR);
        provider.failing = false;

        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("next question");
        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("next question");
        assertThat(provider.calls).isEqualTo(5);
        assertThat(logs.events("agent.model.circuit.closed")).singleElement()
                .satisfies(line -> assertThat(line).containsEntry("level", "INFO"));
    }

    @Test
    void reopensWhenTheTrialCallFails() {
        provider.failing = true;
        failThreeTimes();
        clock.advance(OPEN_FOR);

        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        assertThat(provider.calls).isEqualTo(4);
        assertThat(logs.events("agent.model.circuit.opened")).hasSize(2);
    }

    @Test
    void doesNotCountBugsAsProviderFailures() {
        provider.bug = true;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(IllegalStateException.class);
        }
        provider.bug = false;

        assertThat(model.nextTurn(TRANSCRIPT).message()).isEqualTo("next question");
    }

    private void failTwice() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
        }
    }

    private void failThreeTimes() {
        failTwice();
        assertThatThrownBy(() -> model.nextTurn(TRANSCRIPT)).isInstanceOf(AgentModelUnavailableException.class);
    }

    private static final class FakeModel implements AgentModelPort {

        private int calls;
        private boolean failing;
        private boolean bug;

        @Override
        public AgentReply nextTurn(List<Turn> transcript) {
            answer();
            return new AgentReply("next question", false);
        }

        @Override
        public SummaryDraft summarize(List<Turn> transcript) {
            answer();
            return new SummaryDraft("headache", null, null, null);
        }

        private void answer() {
            calls++;
            if (bug) {
                throw new IllegalStateException("a bug, not the provider");
            }
            if (failing) {
                throw new AgentModelUnavailableException("The AI provider is not available right now.");
            }
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-27T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
