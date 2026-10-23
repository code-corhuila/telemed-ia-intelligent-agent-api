package com.telemed.intelligentagent.domain.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules every AI provider receives, whatever adapter sends them. */
class AgentInstructionsTest {

    @Test
    void limitTheModelToCollectingAndOrganisingInformation() {
        assertThat(AgentInstructions.CONVERSATION)
                .contains("Never diagnose")
                .contains("never recommend or name medication")
                .contains("one short question")
                .contains("Spanish");
    }

    @Test
    void keepTheModelAskingUntilTheWholePictureIsThere() {
        assertThat(AgentInstructions.CONVERSATION)
                .contains("message is always your next short question")
                .contains("allergies")
                .contains("enoughInformation to false");
    }

    @Test
    void askForASummaryOfReportedFactsOnly() {
        assertThat(AgentInstructions.SUMMARY)
                .contains("only what the patient said")
                .contains("null")
                .contains("no diagnosis");
    }
}
