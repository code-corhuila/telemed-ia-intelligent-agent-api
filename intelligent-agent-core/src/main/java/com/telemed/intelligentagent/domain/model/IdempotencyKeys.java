package com.telemed.intelligentagent.domain.model;

import java.util.regex.Pattern;

/** The caller's Idempotency-Key, as agent-db bounds it: 8 to 128 printable ASCII characters. */
public final class IdempotencyKeys {

    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{8,128}");

    private IdempotencyKeys() {
    }

    public static boolean isValid(String key) {
        return key != null && KEY.matcher(key).matches();
    }
}
