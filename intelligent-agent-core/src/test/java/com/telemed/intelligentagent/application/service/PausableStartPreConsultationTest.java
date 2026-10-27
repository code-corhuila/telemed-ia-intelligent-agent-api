package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.PreConsultationsPausedException;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.domain.model.Conversation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runbook mitigation 1: while the agent is degraded, new pre-consultations are paused. */
class PausableStartPreConsultationTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();

    private final AtomicInteger starts = new AtomicInteger();
    private final StartPreConsultationUseCase delegate = (patient, appointment, key) -> {
        starts.incrementAndGet();
        return Conversation.start(patient, appointment, 8, Instant.parse("2026-10-27T15:00:00Z"));
    };

    @Test
    void startsAsUsualWhenNotPaused() {
        Conversation conversation = new PausableStartPreConsultation(delegate, () -> false)
                .start(PATIENT, APPOINTMENT, "start-key-0001");

        assertThat(conversation.appointmentId()).isEqualTo(APPOINTMENT);
        assertThat(starts).hasValue(1);
    }

    @Test
    void refusesNewPreConsultationsWhilePausedWithoutTouchingAnything() {
        assertThatThrownBy(() -> new PausableStartPreConsultation(delegate, () -> true)
                .start(PATIENT, APPOINTMENT, "start-key-0001"))
                .isInstanceOf(PreConsultationsPausedException.class);
        assertThat(starts).hasValue(0);
    }

    @Test
    void readsTheSwitchOnEveryRequest() {
        boolean[] paused = {true};
        PausableStartPreConsultation start = new PausableStartPreConsultation(delegate, () -> paused[0]);

        assertThatThrownBy(() -> start.start(PATIENT, APPOINTMENT, "start-key-0001"))
                .isInstanceOf(PreConsultationsPausedException.class);
        paused[0] = false;
        start.start(PATIENT, APPOINTMENT, "start-key-0001");

        assertThat(starts).hasValue(1);
    }
}
