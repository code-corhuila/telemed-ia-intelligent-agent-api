package com.telemed.intelligentagent.adapter.out.ai;

import com.telemed.intelligentagent.application.port.out.AgentModelPort.AgentReply;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.SummaryDraft;
import com.telemed.intelligentagent.application.port.out.AgentModelPort.Turn;
import com.telemed.intelligentagent.domain.model.Sender;
import com.telemed.intelligentagent.domain.safety.ClinicalSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A deterministic model: tests and local runs need no AI provider or API key. */
class ScriptedAgentModelTest {

    private final ScriptedAgentModel model = new ScriptedAgentModel();

    @Test
    void asksItsFollowUpQuestionsInOrderAndThenHasEnough() {
        List<Turn> transcript = new ArrayList<>();
        transcript.add(new Turn(Sender.PATIENT, "Me duele la cabeza"));

        AgentReply first = model.nextTurn(transcript);
        transcript.add(new Turn(Sender.AGENT, first.message()));
        transcript.add(new Turn(Sender.PATIENT, "Desde hace dos días"));
        AgentReply second = model.nextTurn(transcript);

        assertThat(first.message()).isEqualTo(ScriptedAgentModel.QUESTIONS.get(0));
        assertThat(second.message()).isEqualTo(ScriptedAgentModel.QUESTIONS.get(1));
        assertThat(first.enoughInformation()).isFalse();

        // Every scripted question asked and answered: nothing left to ask.
        for (int i = 1; i < ScriptedAgentModel.QUESTIONS.size(); i++) {
            transcript.add(new Turn(Sender.AGENT, ScriptedAgentModel.QUESTIONS.get(i)));
            transcript.add(new Turn(Sender.PATIENT, "respuesta " + i));
        }
        assertThat(model.nextTurn(transcript).enoughInformation()).isTrue();
    }

    @Test
    void countsOnlyItsOwnQuestionsNotTheOpeningOrOtherAgentTurns() {
        List<Turn> transcript = new ArrayList<>(List.of(
                new Turn(Sender.AGENT, "¿Qué te trae?"),
                new Turn(Sender.PATIENT, "Me duele la cabeza")));

        assertThat(model.nextTurn(transcript).message()).isEqualTo(ScriptedAgentModel.QUESTIONS.get(0));

        transcript.add(new Turn(Sender.AGENT, ScriptedAgentModel.QUESTIONS.get(0)));
        transcript.add(new Turn(Sender.PATIENT, "¿Qué medicamento tomo?"));
        transcript.add(new Turn(Sender.AGENT, ClinicalSafetyPolicy.BOUNDARY_MESSAGE));
        transcript.add(new Turn(Sender.PATIENT, "Desde ayer"));
        assertThat(model.nextTurn(transcript).message()).isEqualTo(ScriptedAgentModel.QUESTIONS.get(1));
    }

    @Test
    void summarisesOnlyWhatThePatientSaid() {
        List<Turn> transcript = List.of(
                new Turn(Sender.PATIENT, "Me duele la cabeza"),
                new Turn(Sender.AGENT, ScriptedAgentModel.QUESTIONS.get(0)),
                new Turn(Sender.PATIENT, "Desde hace dos días"),
                new Turn(Sender.AGENT, ScriptedAgentModel.QUESTIONS.get(1)),
                new Turn(Sender.PATIENT, "Un 6 de 10, me molesta la luz"),
                new Turn(Sender.AGENT, ScriptedAgentModel.QUESTIONS.get(2)),
                new Turn(Sender.PATIENT, "Tengo migraña desde joven"));

        SummaryDraft summary = model.summarize(transcript);

        assertThat(summary.consultationReason()).isEqualTo("Me duele la cabeza");
        assertThat(summary.evolutionTime()).isEqualTo("Desde hace dos días");
        assertThat(summary.detailedSymptoms()).isEqualTo("Un 6 de 10, me molesta la luz");
        assertThat(summary.relevantHistory()).isEqualTo("Tengo migraña desde joven");
    }

    @Test
    void leavesUnansweredFieldsEmptyInsteadOfInventingThem() {
        SummaryDraft summary = model.summarize(List.of(new Turn(Sender.PATIENT, "Tengo tos")));

        assertThat(summary.consultationReason()).isEqualTo("Tengo tos");
        assertThat(summary.evolutionTime()).isNull();
        assertThat(summary.detailedSymptoms()).isNull();
        assertThat(summary.relevantHistory()).isNull();
    }

    @Test
    void asksAtLeastTheAgentsDefaultMinimumOfQuestions() {
        // Fewer would leave the scripted model unable to close under AGENT_MINIMUM_QUESTIONS=4.
        assertThat(ScriptedAgentModel.QUESTIONS).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void neverSaysAnythingTheSafetyPolicyWouldBlock() {
        ClinicalSafetyPolicy policy = new ClinicalSafetyPolicy();

        ScriptedAgentModel.QUESTIONS.forEach(question -> assertThat(policy.safeReply(question)).isEqualTo(question));
    }
}
