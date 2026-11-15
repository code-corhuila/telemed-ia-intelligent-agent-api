package com.telemed.intelligentagent.domain.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Least privilege towards the AI provider: it never receives personal identifiers. */
class PersonalDataRedactorTest {

    private final PersonalDataRedactor redactor = new PersonalDataRedactor();

    @Test
    void hidesEmailAddresses() {
        assertThat(redactor.redact("Escríbanme a ana.rojas@correo.com por favor"))
                .isEqualTo("Escríbanme a [correo] por favor");
    }

    @Test
    void hidesDocumentAndPhoneNumbers() {
        assertThat(redactor.redact("Mi cédula es 1075234567 y mi celular 300 123 4567"))
                .isEqualTo("Mi cédula es [número] y mi celular [número]");
        assertThat(redactor.redact("Llámenme al +57 3001234567"))
                .isEqualTo("Llámenme al [número]");
    }

    @Test
    void keepsClinicallyUsefulNumbers() {
        assertThat(redactor.redact("Tengo 38,5 de fiebre desde hace 3 días, el dolor es 7 de 10 y peso 65 kg"))
                .isEqualTo("Tengo 38,5 de fiebre desde hace 3 días, el dolor es 7 de 10 y peso 65 kg");
        assertThat(redactor.redact("Tomé 500 mg a las 10:30")).isEqualTo("Tomé 500 mg a las 10:30");
    }
}
