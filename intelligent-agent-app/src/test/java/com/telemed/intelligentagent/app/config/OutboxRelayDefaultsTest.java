package com.telemed.intelligentagent.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** HU-06: the outbox relay and the broker settings it relies on. */
class OutboxRelayDefaultsTest {

    @Test
    void relayRunsOnlyWhereItIsTurnedOn() throws IOException {
        MockEnvironment environment = applicationYml();

        // Off unless OUTBOX_RELAY_ENABLED=true, so tests and tools never publish by accident.
        assertThat(environment.getProperty("agent.outbox-relay.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("agent.outbox-relay.interval")).isEqualTo("PT5S");
        assertThat(environment.getProperty("agent.outbox-relay.batch-size", Integer.class)).isEqualTo(10);
        assertThat(environment.getProperty("agent.outbox-relay.confirm-timeout")).isEqualTo("PT5S");
    }

    @Test
    void brokerConfirmsAndReturnsEveryPublishedEvent() throws IOException {
        MockEnvironment environment = applicationYml();

        assertThat(environment.getProperty("spring.rabbitmq.publisher-confirm-type")).isEqualTo("correlated");
        assertThat(environment.getProperty("spring.rabbitmq.publisher-returns", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.template.mandatory", Boolean.class)).isTrue();
    }

    private static MockEnvironment applicationYml() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
