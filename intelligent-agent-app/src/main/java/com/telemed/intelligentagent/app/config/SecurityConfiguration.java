package com.telemed.intelligentagent.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.in.http.security.ApiErrorSecurityHandlers;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Least privilege at the door: stateless, only identity-api's RS256 tokens,
 * only the PATIENT role on the agent's API, everything else denied.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, ObjectMapper objectMapper)
            throws Exception {
        ApiErrorSecurityHandlers handlers = new ApiErrorSecurityHandlers(objectMapper);

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Spring's own error dispatch, so an unknown path ends in 404, not 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        // The summary is the professional's; everything else is the patient's own conversation.
                        .requestMatchers("/api/v1/agent/preconsultations", "/api/v1/agent/preconsultations/**")
                        .hasRole("PROFESSIONAL")
                        .requestMatchers("/api/v1/agent/**").hasRole("PATIENT")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint(handlers.unauthorized())
                        .accessDeniedHandler(handlers.forbidden()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.unauthorized())
                        .accessDeniedHandler(handlers.forbidden()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(readPublicKey(Path.of(properties.publicKeyFile())))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        // Signature, expiry (with Spring's default clock skew) and issuer.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer())));
        return decoder;
    }

    /** identity-api puts the role in a single "role" claim (PATIENT, PROFESSIONAL, ADMIN). */
    private static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static RSAPublicKey readPublicKey(Path pem) throws Exception {
        String base64 = Files.readString(pem)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
