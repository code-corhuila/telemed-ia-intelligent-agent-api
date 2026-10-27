package com.telemed.intelligentagent.app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** The agent's limits and its AI provider. The scripted model needs no key and is the default. */
@Validated
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @DefaultValue("8") @Min(1) @Max(20) int questionLimit,
        @DefaultValue("4") @Min(0) @Max(20) int minimumQuestions,
        @DefaultValue @Valid @NotNull Model model,
        @DefaultValue @Valid @NotNull Appointments appointments,
        @DefaultValue @Valid @NotNull Mongo mongo
) {

    /** The agent may never be required to ask more than it is allowed to. */
    @AssertTrue(message = "minimum-questions must not exceed question-limit")
    public boolean isMinimumWithinLimit() {
        return minimumQuestions <= questionLimit;
    }

    /** appointment-scheduling-api; without a base URL the agent fails closed. */
    public record Appointments(
            String baseUrl,
            @DefaultValue("3s") @NotNull Duration timeout
    ) {
    }

    /** Runbook mitigation 4: how long a request may wait on agent-db before answering 503. */
    public record Mongo(
            @DefaultValue("2s") @NotNull Duration serverSelectionTimeout,
            @DefaultValue("2s") @NotNull Duration connectTimeout,
            @DefaultValue("5s") @NotNull Duration readTimeout
    ) {

        // Zero means "wait forever" to the driver, which is what these settings are here to prevent.
        @AssertTrue(message = "agent-db timeouts must be positive")
        public boolean isEveryTimeoutPositive() {
            return serverSelectionTimeout == null || connectTimeout == null || readTimeout == null
                    || serverSelectionTimeout.isPositive() && connectTimeout.isPositive() && readTimeout.isPositive();
        }
    }

    public enum Provider {
        SCRIPTED,
        GROQ
    }

    public record Model(
            @DefaultValue("scripted") @NotNull Provider provider,
            @DefaultValue @Valid @NotNull Groq groq
    ) {
    }

    public record Groq(
            @DefaultValue("https://api.groq.com/openai/v1") @NotBlank String baseUrl,
            String apiKey,
            @DefaultValue("openai/gpt-oss-20b") @NotBlank String model,
            @DefaultValue("20s") @NotNull Duration timeout,
            @DefaultValue("3") @Min(1) @Max(5) int attempts,
            @DefaultValue("300ms") @NotNull Duration backoff
    ) {

        /** The key never reaches a log or an error message. */
        @Override
        public String toString() {
            return "Groq[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<set>")
                    + ", model=" + model + ", timeout=" + timeout + ", attempts=" + attempts
                    + ", backoff=" + backoff + "]";
        }
    }
}
