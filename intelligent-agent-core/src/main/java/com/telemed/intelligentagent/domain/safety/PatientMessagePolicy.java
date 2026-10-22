package com.telemed.intelligentagent.domain.safety;

import com.telemed.intelligentagent.domain.model.AgentDomainException;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.MESSAGE_TOO_LONG;

/** What the agent accepts from the patient in one turn. */
public final class PatientMessagePolicy {

    public static final int MAX_LENGTH = 1000;

    /** Strips control characters (keeping line breaks) and trims; rejects empty or overlong text. */
    public String clean(String message) {
        if (message == null) {
            throw new AgentDomainException(INVALID_MESSAGE, "Write what you want to tell the assistant.");
        }
        String cleaned = message.replaceAll("[\\p{Cc}&&[^\\n]]", "").strip();
        if (cleaned.isEmpty()) {
            throw new AgentDomainException(INVALID_MESSAGE, "Write what you want to tell the assistant.");
        }
        if (cleaned.length() > MAX_LENGTH) {
            throw new AgentDomainException(MESSAGE_TOO_LONG,
                    "A message can have at most " + MAX_LENGTH + " characters.");
        }
        return cleaned;
    }
}
