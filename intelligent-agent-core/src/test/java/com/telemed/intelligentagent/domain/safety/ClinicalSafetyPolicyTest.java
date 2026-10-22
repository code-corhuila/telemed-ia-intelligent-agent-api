package com.telemed.intelligentagent.domain.safety;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 and HU-06 AC4: the agent never diagnoses, prescribes or replaces the professional. */
class ClinicalSafetyPolicyTest {

    private final ClinicalSafetyPolicy policy = new ClinicalSafetyPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "¿Qué enfermedad tengo?",
            "Dime cuál es mi diagnóstico",
            "DIAGNOSTICAME por favor",
            "¿Qué medicamento debo tomar?",
            "que pastilla me tomo para el dolor",
            "¿Puedo tomar ibuprofeno?",
            "Recétame algo",
            "¿Qué dosis de acetaminofén me tomo?",
            "¿Qué tratamiento debo seguir?",
            "¿Necesito antibiótico?"
    })
    void recognisesRequestsForAClinicalDecision(String message) {
        assertThat(policy.asksForClinicalDecision(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Me duele la cabeza desde hace dos días",
            "Ayer tomé acetaminofén y no me hizo efecto",
            "Mi médico me diagnosticó hipertensión hace un año",
            "Tengo fiebre de 38,5 en las noches"
    })
    void letsThePatientReportWhatTheyFeelOrAlreadyTake(String message) {
        assertThat(policy.asksForClinicalDecision(message)).isFalse();
    }

    @Test
    void answersClinicalRequestsWithTheBoundaryMessage() {
        assertThat(ClinicalSafetyPolicy.BOUNDARY_MESSAGE)
                .contains("No puedo dar diagnósticos")
                .contains("profesional de la salud");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Probablemente tienes migraña.",
            "Tu diagnóstico es una infección.",
            "Te recomiendo tomar ibuprofeno cada 8 horas.",
            "La dosis recomendada es de 500 mg.",
            "Debes iniciar tratamiento con antibióticos.",
            "Parece que usted tiene gastritis."
    })
    void replacesModelRepliesThatDiagnoseOrPrescribe(String reply) {
        assertThat(policy.safeReply(reply)).isEqualTo(ClinicalSafetyPolicy.BOUNDARY_MESSAGE);
    }

    @Test
    void tellsWhetherATextCrossesTheClinicalLine() {
        assertThat(policy.crossesClinicalLine("Probablemente tiene migraña")).isTrue();
        assertThat(policy.crossesClinicalLine("Dolor de cabeza desde hace dos días")).isFalse();
        assertThat(policy.crossesClinicalLine(null)).isFalse();
    }

    @Test
    void keepsSafeFollowUpQuestions() {
        String reply = "¿Desde cuándo tienes el dolor y qué tan fuerte es del 1 al 10?";

        assertThat(policy.safeReply(reply)).isEqualTo(reply);
    }

    @Test
    void neverShowsAnEmptyOrOverlongReply() {
        assertThat(policy.safeReply("  ")).isEqualTo(ClinicalSafetyPolicy.BOUNDARY_MESSAGE);
        assertThat(policy.safeReply(null)).isEqualTo(ClinicalSafetyPolicy.BOUNDARY_MESSAGE);
        assertThat(policy.safeReply("¿Algo más? ".repeat(500))).hasSizeLessThanOrEqualTo(ClinicalSafetyPolicy.MAX_REPLY_LENGTH);
    }
}
