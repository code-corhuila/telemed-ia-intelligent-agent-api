package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.application.event.OutboxEvent;

import java.util.List;
import java.util.UUID;

/** Relay side of agent-db's outbox_events; the API writes the events with the summary. */
public interface OutboxEventsPort {

    /** Up to {@code limit} events still PENDING, oldest first. */
    List<OutboxEvent> pending(int limit);

    /** Marks the event PUBLISHED; an event another relay already marked is left as it is. */
    void markPublished(UUID eventId);
}
