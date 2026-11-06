package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.application.event.OutboxEvent;

/** The message broker; publishing counts only when a queue took the event. */
public interface IntegrationEventPublisherPort {

    /** @throws EventPublicationException when the broker did not take the event */
    void publish(OutboxEvent event);
}
