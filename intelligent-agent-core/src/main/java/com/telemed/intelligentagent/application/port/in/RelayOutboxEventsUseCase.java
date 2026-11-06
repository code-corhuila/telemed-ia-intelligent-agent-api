package com.telemed.intelligentagent.application.port.in;

/** Publishes the pending outbox events to the broker (transactional outbox, NORMA 5.3.11). */
public interface RelayOutboxEventsUseCase {

    /** @return how many events the broker took in this run */
    int relayPending();
}
