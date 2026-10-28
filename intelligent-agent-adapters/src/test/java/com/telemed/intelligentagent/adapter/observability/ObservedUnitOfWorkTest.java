package com.telemed.intelligentagent.adapter.observability;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedUnitOfWorkTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final List<String> calls = new ArrayList<>();

    @Test
    void runsTheWorkInsideAnAgentDbSpan() {
        ObservedUnitOfWork unitOfWork = new ObservedUnitOfWork(work -> {
            calls.add("transaction");
            work.run();
        }, registry);

        unitOfWork.execute(() -> calls.add("work"));

        assertThat(calls).containsExactly("transaction", "work");
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("agent.mongodb.transaction").that()
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void marksTheSpanFailedAndRethrowsWhenTheTransactionFails() {
        IllegalStateException failure = new IllegalStateException("agent-db unavailable");
        ObservedUnitOfWork unitOfWork = new ObservedUnitOfWork(work -> {
            throw failure;
        }, registry);

        assertThatThrownBy(() -> unitOfWork.execute(() -> { })).isSameAs(failure);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("agent.mongodb.transaction").that()
                .hasError(failure)
                .hasBeenStopped();
    }
}
