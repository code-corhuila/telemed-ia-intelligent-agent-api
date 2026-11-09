package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.adapter.out.ai.CircuitBreakingAgentModel;
import com.telemed.intelligentagent.adapter.out.ai.GroqAgentModel;
import com.telemed.intelligentagent.adapter.out.ai.ScriptedAgentModel;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Picks the AI provider at startup; Groq without GROQ_API_KEY fails here instead of on the first patient. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentModelConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AgentModelPort agentModel(AgentProperties properties, Clock clock, ObjectProvider<ObservationRegistry> registry) {
        AgentProperties.Model model = properties.model();
        return switch (model.provider()) {
            case SCRIPTED -> new ScriptedAgentModel();
            case GROQ -> {
                AgentProperties.Groq groq = model.groq();
                AgentProperties.Circuit circuit = model.circuit();
                yield new CircuitBreakingAgentModel(
                        new GroqAgentModel(groq.baseUrl(), groq.apiKey(), groq.model(), groq.timeout(),
                                groq.attempts(), groq.backoff(), registry.getIfAvailable(() -> ObservationRegistry.NOOP)),
                        circuit.failureThreshold(), circuit.openFor(), clock);
            }
        };
    }
}
