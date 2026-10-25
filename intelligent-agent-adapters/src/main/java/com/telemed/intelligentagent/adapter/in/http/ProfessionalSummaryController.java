package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.application.port.in.ReadSummaryForProfessionalUseCase;
import com.telemed.intelligentagent.domain.model.PreConsultationSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The professional reads the summary before attending; the patient never reaches this (PROFESSIONAL role only). */
@RestController
@RequestMapping("/api/v1/agent/preconsultations")
public class ProfessionalSummaryController {

    private final ReadSummaryForProfessionalUseCase read;

    public ProfessionalSummaryController(ReadSummaryForProfessionalUseCase read) {
        this.read = read;
    }

    /** The contract's PreconsultationSummary (camelCase, UUIDs, RFC 3339 UTC); absent fields are null. */
    public record SummaryResponse(UUID id, UUID conversationId, UUID appointmentId, UUID patientId,
                                  String consultationReason, String evolutionTime, String detailedSymptoms,
                                  String relevantHistory, Instant createdAt) {

        static SummaryResponse of(PreConsultationSummary summary) {
            return new SummaryResponse(summary.id(), summary.conversationId(), summary.appointmentId(),
                    summary.patientId(), summary.consultationReason(), summary.evolutionTime(),
                    summary.detailedSymptoms(), summary.relevantHistory(), summary.createdAt());
        }
    }

    @GetMapping
    public SummaryResponse byAppointment(@AuthenticationPrincipal Jwt token, @RequestParam UUID appointmentId) {
        return SummaryResponse.of(read.read(TokenSubject.of(token), appointmentId));
    }
}
