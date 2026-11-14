package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.in.RelayOutboxEventsUseCase;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import com.telemed.intelligentagent.application.port.out.IntegrationEventPublisherPort;
import com.telemed.intelligentagent.application.port.out.OutboxEventsPort;

/**
 * At-least-once relay: an event is marked published only after the broker took it, so a crash
 * between the two publishes it again; consumers are idempotent by the event id.
 */
public class RelayOutboxEventsService implements RelayOutboxEventsUseCase {

    private final OutboxEventsPort outbox;
    private final IntegrationEventPublisherPort publisher;
    private final int batchSize;

    public RelayOutboxEventsService(OutboxEventsPort outbox, IntegrationEventPublisherPort publisher, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Relay batch size must be positive.");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Override
    public int relayPending() {
        int published = 0;
        for (OutboxEvent event : outbox.pending(batchSize)) {
            try {
                publisher.publish(event);
            } catch (EventPublicationException refused) {
                // It stays pending for the next run; one refused event does not hold back the rest.
                continue;
            }
            outbox.markPublished(event.id());
            published++;
        }
        return published;
    }
}
