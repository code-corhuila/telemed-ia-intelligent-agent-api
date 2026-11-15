package com.telemed.intelligentagent.adapter.out.messaging;

import com.telemed.intelligentagent.adapter.in.http.observability.TraceContext;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.event.OutboxEvent;
import com.telemed.intelligentagent.application.port.out.EventPublicationException;
import com.telemed.intelligentagent.application.port.out.IntegrationEventPublisherPort;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Week 11 (CP3): publishes each outbox event inside the trace of the request that recorded it,
 * under a PRODUCER span of its own, and logs the outcome. With a tracer the span is exported, so
 * Jaeger shows the publication under the /finish request. Only the event identity is logged.
 */
public class TracingIntegrationEventPublisher implements IntegrationEventPublisherPort {

    private static final Logger LOG = LoggerFactory.getLogger(TracingIntegrationEventPublisher.class);

    private final IntegrationEventPublisherPort delegate;
    // Null without a tracer: the relay's span id is then made here and only logged.
    private final Tracer tracer;

    public TracingIntegrationEventPublisher(IntegrationEventPublisherPort delegate) {
        this(delegate, null);
    }

    public TracingIntegrationEventPublisher(IntegrationEventPublisherPort delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    @Override
    public void publish(OutboxEvent event) {
        Map<String, String> relayContext = MDC.getCopyOfContextMap();
        Span span = startSpan(event);
        long started = System.nanoTime();
        try (Tracer.SpanInScope scope = span == null ? null : tracer.withSpan(span)) {
            TraceContext trace = span == null
                    ? TraceContext.from(event.traceparent())
                    : new TraceContext(span.context().traceId(), span.context().spanId());
            MDC.put(CurrentTrace.TRACE_ID, trace.traceId());
            MDC.put(CurrentTrace.SPAN_ID, trace.spanId());
            if (event.correlationId() != null) {
                MDC.put(CurrentTrace.CORRELATION_ID, event.correlationId());
            }

            delegate.publish(event);
            LOG.atInfo()
                    .addKeyValue("event", "outbox.event.published")
                    .addKeyValue("event_type", event.eventType())
                    .addKeyValue("event_id", event.id().toString())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("outbox event published");
        } catch (EventPublicationException refused) {
            LOG.atWarn()
                    .addKeyValue("event", "outbox.event.failed")
                    .addKeyValue("event_type", event.eventType())
                    .addKeyValue("event_id", event.id().toString())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("outbox event not published; it stays pending");
            if (span != null) {
                span.error(refused);
            }
            throw refused;
        } finally {
            if (span != null) {
                span.end();
            }
            if (relayContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(relayContext);
            }
        }
    }

    /** A producer span under the span of the request that recorded the event, or a new trace. */
    private Span startSpan(OutboxEvent event) {
        if (tracer == null) {
            return null;
        }
        Span.Builder span = tracer.spanBuilder()
                .name(RabbitIntegrationEventPublisher.EXCHANGE + " publish")
                .kind(Span.Kind.PRODUCER)
                .tag("messaging.destination.name", RabbitIntegrationEventPublisher.EXCHANGE)
                .tag("event_type", event.eventType());
        TraceContext request = TraceContext.parse(event.traceparent());
        if (request == null) {
            span.setNoParent();
        } else {
            span.setParent(tracer.traceContextBuilder()
                    .traceId(request.traceId())
                    .spanId(request.spanId())
                    .sampled(true)
                    .build());
        }
        return span.start();
    }
}
