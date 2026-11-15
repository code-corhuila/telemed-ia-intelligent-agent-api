package com.telemed.intelligentagent.app.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Runbook mitigation 4: bounded waits on agent-db. The driver's defaults are 30 s to find a
 * server and no read timeout at all, so a stalled agent-db would hold every worker thread.
 * Runs after Boot's own customizer, so these values win over the same options in MONGODB_URI.
 */
@Configuration(proxyBeanMethods = false)
public class MongoClientConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer agentDbTimeouts(AgentProperties properties) {
        AgentProperties.Mongo mongo = properties.mongo();
        return settings -> settings
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(mongo.serverSelectionTimeout().toMillis(), MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(mongo.connectTimeout().toMillis(), MILLISECONDS)
                        .readTimeout(mongo.readTimeout().toMillis(), MILLISECONDS));
    }
}
