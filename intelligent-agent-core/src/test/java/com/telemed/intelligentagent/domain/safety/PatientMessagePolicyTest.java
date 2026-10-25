package com.telemed.intelligentagent.domain.safety;

import com.telemed.intelligentagent.domain.model.AgentDomainException;
import org.junit.jupiter.api.Test;

import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.INVALID_MESSAGE;
import static com.telemed.intelligentagent.domain.model.AgentDomainException.Reason.MESSAGE_TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientMessagePolicyTest {

    private final PatientMessagePolicy policy = new PatientMessagePolicy();

    @Test
    void trimsAndDropsControlCharactersButKeepsLineBreaks() {
        assertThat(policy.clean("  Me duele\u0000 la\u0007 cabeza\ndesde ayer  "))
                .isEqualTo("Me duele la cabeza\ndesde ayer");
    }

    @Test
    void rejectsEmptyMessages() {
        assertThatThrownBy(() -> policy.clean("   "))
                .isInstanceOf(AgentDomainException.class)
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
        assertThatThrownBy(() -> policy.clean(null))
                .extracting("reason").isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void rejectsMessagesOverTheLimit() {
        assertThat(policy.clean("a".repeat(PatientMessagePolicy.MAX_LENGTH))).hasSize(PatientMessagePolicy.MAX_LENGTH);
        assertThatThrownBy(() -> policy.clean("a".repeat(PatientMessagePolicy.MAX_LENGTH + 1)))
                .extracting("reason").isEqualTo(MESSAGE_TOO_LONG);
    }
}
