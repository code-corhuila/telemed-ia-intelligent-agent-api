package com.telemed.intelligentagent.adapter.in.http;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** identity-api's subject is the user's UUID (patient or professional); anything else is not a usable credential. */
final class TokenSubject {

    private TokenSubject() {
    }

    static UUID of(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (RuntimeException notAUuid) {
            throw new InvalidSubjectException();
        }
    }

    static final class InvalidSubjectException extends RuntimeException {
    }
}
