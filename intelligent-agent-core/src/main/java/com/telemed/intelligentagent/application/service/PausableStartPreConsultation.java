package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.PreConsultationsPausedException;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.domain.model.Conversation;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Runbook mitigation 1: while the AI provider or agent-db is degraded, operators pause new
 * pre-consultations and patients book without one. Conversations already started go on.
 */
public class PausableStartPreConsultation implements StartPreConsultationUseCase {

    private final StartPreConsultationUseCase start;
    private final BooleanSupplier paused;

    public PausableStartPreConsultation(StartPreConsultationUseCase start, BooleanSupplier paused) {
        this.start = start;
        this.paused = paused;
    }

    @Override
    public Conversation start(UUID patientId, UUID appointmentId, String idempotencyKey) {
        if (paused.getAsBoolean()) {
            throw new PreConsultationsPausedException();
        }
        return start.start(patientId, appointmentId, idempotencyKey);
    }
}
