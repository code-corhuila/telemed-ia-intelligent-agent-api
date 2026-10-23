package com.telemed.intelligentagent.adapter.out.ai;

import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.domain.model.Sender;

import java.util.List;

/**
 * Deterministic stand-in for an AI provider: fixed follow-up questions,
 * and a summary that maps the patient's answers to the fields in order.
 * Used in tests and whenever no provider is configured.
 */
public class ScriptedAgentModel implements AgentModelPort {

    static final List<String> QUESTIONS = List.of(
            "Gracias por contarme. ¿Desde cuándo lo sientes?",
            "¿Qué tan intenso es, del 1 al 10, y has notado otros síntomas?",
            "¿Tienes alguna condición de salud o antecedente que tu médico deba conocer?"
    );

    @Override
    public AgentReply nextTurn(List<Turn> transcript) {
        // Only its own questions count: the opening and boundary replies are agent turns too.
        int asked = (int) transcript.stream()
                .filter(turn -> turn.sender() == Sender.AGENT && QUESTIONS.contains(turn.text()))
                .count();
        if (asked >= QUESTIONS.size()) {
            return new AgentReply("Gracias, ya tengo la información para tu médico.", true);
        }
        return new AgentReply(QUESTIONS.get(asked), false);
    }

    @Override
    public SummaryDraft summarize(List<Turn> transcript) {
        List<String> answers = transcript.stream()
                .filter(turn -> turn.sender() == Sender.PATIENT)
                .map(Turn::text)
                .toList();
        return new SummaryDraft(answer(answers, 0), answer(answers, 1), answer(answers, 2), answer(answers, 3));
    }

    private static String answer(List<String> answers, int index) {
        return index < answers.size() ? answers.get(index) : null;
    }
}
