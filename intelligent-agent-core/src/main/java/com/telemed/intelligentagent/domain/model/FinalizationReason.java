package com.telemed.intelligentagent.domain.model;

/** Why a pre-consultation ended: the patient was done, or the agent ran out of questions. */
public enum FinalizationReason {
    COMPLETED,
    QUESTION_LIMIT_REACHED
}
