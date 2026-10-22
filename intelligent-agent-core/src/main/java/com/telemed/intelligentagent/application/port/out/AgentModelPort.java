package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.domain.model.Sender;

import java.util.List;

/**
 * The AI model as the agent sees it (ADR-008): no provider, SDK or prompt
 * format leaks into the core. Every transcript handed over has already
 * gone through PersonalDataRedactor; every reply still goes through
 * ClinicalSafetyPolicy before the patient sees it.
 */
public interface AgentModelPort {

    /** The agent's next follow-up question, and whether it already has enough to summarise. */
    AgentReply nextTurn(List<Turn> transcript);

    /** The structured pre-consultation summary, built from what the patient reported. */
    SummaryDraft summarize(List<Turn> transcript);

    record Turn(Sender sender, String text) {
    }

    record AgentReply(String message, boolean enoughInformation) {
    }

    /** Same fields as agent-db's preconsultation_summaries; null when the patient never said it. */
    record SummaryDraft(String consultationReason, String evolutionTime, String detailedSymptoms,
                        String relevantHistory) {
    }
}
