package com.telemed.intelligentagent.adapter.observability;

import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/** Week 11 traces: each agent-db transaction is a span of the request that commits it. */
public class ObservedUnitOfWork implements UnitOfWork {

    private final UnitOfWork transaction;
    private final ObservationRegistry registry;

    public ObservedUnitOfWork(UnitOfWork transaction, ObservationRegistry registry) {
        this.transaction = transaction;
        this.registry = registry;
    }

    @Override
    public void execute(Runnable work) {
        Observation.createNotStarted("agent.mongodb.transaction", registry)
                .contextualName("agent-db transaction")
                .lowCardinalityKeyValue("db.system", "mongodb")
                .observe(() -> transaction.execute(work));
    }
}
