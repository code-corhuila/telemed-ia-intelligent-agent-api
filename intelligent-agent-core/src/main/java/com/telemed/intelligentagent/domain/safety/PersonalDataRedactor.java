package com.telemed.intelligentagent.domain.safety;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Least privilege towards the AI provider: e-mail addresses and long
 * numbers (identity documents, phone numbers) are replaced before any text
 * leaves the service. Short clinical numbers (38,5 · 7 de 10 · 500 mg) stay.
 */
public final class PersonalDataRedactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern NUMBER_RUN = Pattern.compile("\\+?\\d[\\d -]*\\d");
    private static final int MIN_IDENTIFIER_DIGITS = 6;

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String withoutEmails = EMAIL.matcher(text).replaceAll("[correo]");
        Matcher numbers = NUMBER_RUN.matcher(withoutEmails);
        StringBuilder redacted = new StringBuilder();
        while (numbers.find()) {
            long digits = numbers.group().chars().filter(Character::isDigit).count();
            String replacement = digits >= MIN_IDENTIFIER_DIGITS ? "[número]" : numbers.group();
            numbers.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }
        numbers.appendTail(redacted);
        return redacted.toString();
    }
}
