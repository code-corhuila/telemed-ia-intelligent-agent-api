package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.app.observability.AgentRequestMetricsFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Week 11 metrics (CP4), scraped from the management port, which deploy publishes on 127.0.0.1 only. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    // Before security, so rejected requests (401, 403) are counted too.
    @Bean
    FilterRegistrationBean<AgentRequestMetricsFilter> agentRequestMetrics(MeterRegistry registry) {
        FilterRegistrationBean<AgentRequestMetricsFilter> registration =
                new FilterRegistrationBean<>(new AgentRequestMetricsFilter(registry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** Saturation of the async hop: summaries announced but not yet published by the relay. */
    @Bean
    Gauge outboxPendingEvents(MeterRegistry registry, MongoTemplate mongo) {
        return Gauge.builder("agent.outbox.pending.events", () -> mongo.getCollection("outbox_events")
                        .countDocuments(new Document("status", "PENDING")
                                .append("eventType", "PreConsultationSummaryGenerated")))
                .description("Outbox events waiting to be published")
                .tag("event_type", "PreConsultationSummaryGenerated")
                .register(registry);
    }
}
