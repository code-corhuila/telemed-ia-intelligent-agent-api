package com.telemed.intelligentagent.app.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where identity-api's public key lives and which issuer it signs as.
 * The agent only verifies tokens: it never holds a private key.
 */
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String publicKeyFile
) {
}
