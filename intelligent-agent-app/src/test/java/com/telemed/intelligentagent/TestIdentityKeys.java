package com.telemed.intelligentagent;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Stands in for identity-api in tests: its key pair and the tokens it signs. */
final class TestIdentityKeys {

    static final String ISSUER = "telemed-ia-identity-and-access";
    static final KeyPair IDENTITY = rsaKeyPair();

    private TestIdentityKeys() {
    }

    /** Points the agent at a PEM file holding identity's public key. */
    static void register(DynamicPropertyRegistry registry) {
        try {
            Path pem = Files.createTempFile("identity-public", ".pem");
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(IDENTITY.getPublic().getEncoded());
            Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            registry.add("security.jwt.public-key-file", pem::toString);
            registry.add("security.jwt.issuer", () -> ISSUER);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String token(KeyPair keys, String issuer, String subject, String role, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .claim("role", role)
                    .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String patientToken(UUID patientId) {
        return token(IDENTITY, ISSUER, patientId.toString(), "PATIENT", Instant.now().plusSeconds(600));
    }

    static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
