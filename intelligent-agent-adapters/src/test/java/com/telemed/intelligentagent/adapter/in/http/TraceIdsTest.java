package com.telemed.intelligentagent.adapter.in.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One trace id per request, echoed in errors and carried by the outbox as a UUID. */
class TraceIdsTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void keepsOneTraceIdPerRequestEvenWhenTheCallerSentNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(TraceIds.of(request)).isEqualTo(TraceIds.of(request));
    }

    @Test
    void turnsTheTraceIdIntoTheUuidTheOutboxNeeds() {
        UUID uuid = UUID.randomUUID();

        assertThat(TraceIds.asUuid(uuid.toString())).isEqualTo(uuid);
        // The gateway's generated ids are 32 hex characters: the same value, dashed.
        assertThat(TraceIds.asUuid("3bee9f5c753485116f80f29d79c3d6b1"))
                .isEqualTo(UUID.fromString("3bee9f5c-7534-8511-6f80-f29d79c3d6b1"));
        // Anything else maps to a stable UUID, so the same trace always gives the same correlation.
        assertThat(TraceIds.asUuid("corr-123")).isEqualTo(TraceIds.asUuid("corr-123"));
    }

    @Test
    void readsTheCorrelationOfTheRequestBeingServed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.HEADER, "3bee9f5c753485116f80f29d79c3d6b1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TraceIds.currentAsUuid()).isEqualTo(UUID.fromString("3bee9f5c-7534-8511-6f80-f29d79c3d6b1"));
    }

    @Test
    void stillGivesACorrelationOutsideARequest() {
        assertThat(TraceIds.currentAsUuid()).isNotNull();
    }
}
