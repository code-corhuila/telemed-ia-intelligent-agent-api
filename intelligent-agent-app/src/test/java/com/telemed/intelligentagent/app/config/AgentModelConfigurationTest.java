package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.adapter.out.ai.GroqAgentModel;
import com.telemed.intelligentagent.adapter.out.ai.ScriptedAgentModel;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Which AI provider runs is configuration; without a key the agent refuses to start with Groq. */
class AgentModelConfigurationTest {

    private static final String FAKE_KEY = "gsk_test_not_a_real_key_0000000000";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(AgentModelConfiguration.class);

    @Test
    void usesTheScriptedModelUnlessToldOtherwise() {
        context.run(app -> {
            assertThat(app).hasSingleBean(AgentModelPort.class);
            assertThat(app.getBean(AgentModelPort.class)).isInstanceOf(ScriptedAgentModel.class);
            assertThat(app.getBean(AgentProperties.class).questionLimit()).isEqualTo(8);
            assertThat(app.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void usesGroqWhenSelectedWithAKey() {
        context.withPropertyValues("agent.model.provider=groq", "agent.model.groq.api-key=" + FAKE_KEY)
                .run(app -> assertThat(app.getBean(AgentModelPort.class)).isInstanceOf(GroqAgentModel.class));
    }

    @Test
    void refusesToStartWithGroqButNoKey() {
        context.withPropertyValues("agent.model.provider=groq", "agent.model.groq.api-key=")
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void refusesAnUnboundedQuestionLimit() {
        context.withPropertyValues("agent.question-limit=0").run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("agent.question-limit=50").run(app -> assertThat(app).hasFailed());
    }

    @Test
    void neverPrintsTheApiKey() {
        context.withPropertyValues("agent.model.provider=groq", "agent.model.groq.api-key=" + FAKE_KEY)
                .run(app -> assertThat(app.getBean(AgentProperties.class).toString()).doesNotContain(FAKE_KEY));
    }
}
