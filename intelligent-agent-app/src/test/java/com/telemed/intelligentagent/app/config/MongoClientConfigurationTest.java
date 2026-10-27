package com.telemed.intelligentagent.app.config;

import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Runbook mitigation 4: agent-db that does not answer costs a patient seconds, not the driver's 30 s or forever. */
class MongoClientConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(AgentModelConfiguration.class, MongoClientConfiguration.class);

    @Test
    void boundsEveryWaitOnAgentDbByDefault() {
        context.run(app -> {
            MongoClientSettings settings = customized(app.getBean(MongoClientSettingsBuilderCustomizer.class));

            assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(2_000);
            assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(2_000);
            assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(5_000);
        });
    }

    @Test
    void takesTheTimeoutsFromConfiguration() {
        context.withPropertyValues("agent.mongo.server-selection-timeout=1s", "agent.mongo.connect-timeout=500ms",
                        "agent.mongo.read-timeout=3s")
                .run(app -> {
                    MongoClientSettings settings = customized(app.getBean(MongoClientSettingsBuilderCustomizer.class));

                    assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(1_000);
                    assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(500);
                    assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(3_000);
                });
    }

    @Test
    void refusesATimeoutThatWouldWaitForever() {
        context.withPropertyValues("agent.mongo.read-timeout=0s").run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("agent.mongo.server-selection-timeout=0s").run(app -> assertThat(app).hasFailed());
    }

    private static MongoClientSettings customized(MongoClientSettingsBuilderCustomizer customizer) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        customizer.customize(builder);
        return builder.build();
    }
}
