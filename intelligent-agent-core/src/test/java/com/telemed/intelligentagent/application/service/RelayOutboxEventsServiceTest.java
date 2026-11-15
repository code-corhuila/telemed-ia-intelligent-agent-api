package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import com.telemed.intelligentagent.application.port.out.IntegrationEventPublisherPort;
import com.telemed.intelligentagent.application.port.out.OutboxEventsPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayOutboxEventsServiceTest {

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final RecordingPublisher publisher = new RecordingPublisher();

    @Test
    void publishesThePendingEventsAndMarksThemPublished() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        outbox.pending.addAll(List.of(first, second));

        int published = new RelayOutboxEventsService(outbox, publisher, 10).relayPending();

        assertThat(published).isEqualTo(2);
        assertThat(publisher.published).containsExactly(first, second);
        assertThat(outbox.published).containsExactly(first.id(), second.id());
    }

    @Test
    void asksForNoMoreThanTheBatchSize() {
        new RelayOutboxEventsService(outbox, publisher, 3).relayPending();

        assertThat(outbox.requestedLimit).isEqualTo(3);
    }

    @Test
    void leavesAnEventTheBrokerDidNotTakePendingAndGoesOnWithTheRest() {
        OutboxEvent refused = event();
        OutboxEvent taken = event();
        outbox.pending.addAll(List.of(refused, taken));
        publisher.refused.add(refused.id());

        int published = new RelayOutboxEventsService(outbox, publisher, 10).relayPending();

        assertThat(published).isEqualTo(1);
        assertThat(outbox.published).containsExactly(taken.id());
    }

    @Test
    void doesNothingWhenNoEventIsPending() {
        assertThat(new RelayOutboxEventsService(outbox, publisher, 10).relayPending()).isZero();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void refusesANonPositiveBatchSize() {
        assertThatThrownBy(() -> new RelayOutboxEventsService(outbox, publisher, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "PreConsultationSummaryGenerated",
                Instant.parse("2026-11-06T15:00:00Z"), UUID.randomUUID().toString(), null,
                Map.of("summaryId", UUID.randomUUID().toString()));
    }

    private static final class InMemoryOutbox implements OutboxEventsPort {

        final List<OutboxEvent> pending = new ArrayList<>();
        final List<UUID> published = new ArrayList<>();
        int requestedLimit;

        @Override
        public List<OutboxEvent> pending(int limit) {
            requestedLimit = limit;
            return pending.stream().limit(limit).toList();
        }

        @Override
        public void markPublished(UUID eventId) {
            published.add(eventId);
        }
    }

    private static final class RecordingPublisher implements IntegrationEventPublisherPort {

        final List<OutboxEvent> published = new ArrayList<>();
        final Set<UUID> refused = new HashSet<>();

        @Override
        public void publish(OutboxEvent event) {
            if (refused.contains(event.id())) {
                throw new EventPublicationException("refused", null);
            }
            published.add(event);
        }
    }
}
