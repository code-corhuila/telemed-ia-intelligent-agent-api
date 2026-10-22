package com.telemed.intelligentagent.domain.safety;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ADR-008 boundary, enforced on both sides of the model: the patient's
 * request and the model's reply. Patient-reported facts ("tomé
 * acetaminofén") pass; requests for a clinical decision do not.
 */
public final class ClinicalSafetyPolicy {

    public static final String BOUNDARY_MESSAGE =
            "No puedo dar diagnósticos, recomendar medicamentos ni indicar tratamientos: esas decisiones "
                    + "las toma un profesional de la salud en tu consulta. Puedo ayudarte a contar qué sientes, "
                    + "desde cuándo y cómo ha evolucionado.";

    public static final int MAX_REPLY_LENGTH = 1200;

    // Matched on lower-case text without accents or punctuation.
    private static final List<Pattern> CLINICAL_REQUESTS = patterns(
            "\\b(que|cual) (enfermedad|diagnostico)\\b",
            "\\bcual es mi diagnostico\\b",
            "\\bdiagnosti(came|queme|carme)\\b",
            "\\b(que|cual) (medicamento|medicina|pastilla|remedio|tratamiento|dosis)\\b",
            "\\b(puedo|debo|deberia) tomar\\b",
            "\\b(recetame|recetarme|me recetas|prescribeme|prescribirme)\\b",
            "\\bnecesito (un |una )?(antibiotico|medicamento|tratamiento|receta)\\b"
    );

    private static final List<Pattern> CLINICAL_REPLIES = patterns(
            "\\b(probablemente|seguramente|posiblemente) (tienes|tiene|padeces|padece)\\b",
            "\\b(tu|su|el) diagnostico (es|seria)\\b",
            "\\bparece que (usted )?(tienes|tiene)\\b",
            "\\b(te|le) (recomiendo|sugiero) (tomar|usar|aplicar)\\b",
            "\\b(debes|deberias|debe|puedes|puede) (tomar|iniciar tratamiento)\\b",
            "\\bla dosis\\b",
            "\\b\\d+ ?(mg|ml|miligramos|mililitros)\\b",
            "\\b(te|le) (receto|prescribo)\\b",
            "\\b(iniciar|empezar) (un |el )?tratamiento\\b",
            "\\btratamiento recomendado\\b"
    );

    public boolean asksForClinicalDecision(String patientMessage) {
        return matchesAny(CLINICAL_REQUESTS, patientMessage);
    }

    /** The model's reply as the patient may see it: bounded, or the boundary message. */
    public String safeReply(String modelReply) {
        if (modelReply == null || modelReply.isBlank() || matchesAny(CLINICAL_REPLIES, modelReply)) {
            return BOUNDARY_MESSAGE;
        }
        String reply = modelReply.strip();
        return reply.length() <= MAX_REPLY_LENGTH ? reply : reply.substring(0, MAX_REPLY_LENGTH).strip();
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        return patterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[¿?¡!.,;:\"()]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static List<Pattern> patterns(String... regexes) {
        return java.util.Arrays.stream(regexes).map(Pattern::compile).toList();
    }
}
