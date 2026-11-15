package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.SummaryDraft;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.Turn;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.ClinicalSafetyPolicy;
import com.telemed.intelligentagent.domain.safety.PersonalDataRedactor;

import java.time.Instant;
import java.util.List;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.NOTHING_TO_SUMMARIZE;

/**
 * Builds the summary from a redacted transcript. A field that crosses the
 * clinical line, or that is a request for a clinical decision, is dropped;
 * without a usable reason, the patient's first (redacted) report stands in,
 * as agent-db requires one.
 */
public class PreConsultationSummarizer {

    private static final int MAX_FALLBACK_REASON = 500;

    private final AgentModelPort model;
    private final ClinicalSafetyPolicy safetyPolicy = new ClinicalSafetyPolicy();
    private final PersonalDataRedactor redactor = new PersonalDataRedactor();

    public PreConsultationSummarizer(AgentModelPort model) {
        this.model = model;
    }

    public PreConsultationSummary summarize(Conversation conversation, List<Message> transcript, Instant now) {
        // Everything the patient said reaches the model; the agent's boundary replies are only noise.
        List<Turn> turns = transcript.stream()
                .filter(message -> !(message.sender() == Sender.AGENT
                        && ClinicalSafetyPolicy.BOUNDARY_MESSAGE.equals(message.content())))
                .map(message -> new Turn(message.sender(), redactor.redact(message.content())))
                .toList();
        Turn firstReport = turns.stream()
                .filter(turn -> turn.sender() == Sender.PATIENT && !safetyPolicy.asksForClinicalDecision(turn.text()))
                .findFirst()
                .orElseThrow(() -> new AgentDomainException(NOTHING_TO_SUMMARIZE,
                        "Tell the assistant why you are consulting before finishing."));

        SummaryDraft draft = model.summarize(turns);
        String reason = safe(draft.consultationReason());
        if (reason == null) {
            String words = firstReport.text().strip();
            reason = words.length() <= MAX_FALLBACK_REASON ? words : words.substring(0, MAX_FALLBACK_REASON);
        }
        return PreConsultationSummary.of(conversation, reason, safe(draft.evolutionTime()),
                safe(draft.detailedSymptoms()), safe(draft.relevantHistory()), now);
    }

    private String safe(String field) {
        return field == null || field.isBlank() || safetyPolicy.crossesClinicalLine(field)
                || safetyPolicy.asksForClinicalDecision(field) ? null : field;
    }
}
