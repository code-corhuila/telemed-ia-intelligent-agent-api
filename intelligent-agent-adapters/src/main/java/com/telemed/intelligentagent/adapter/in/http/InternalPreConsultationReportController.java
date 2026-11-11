package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase.PreConsultationReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * DEC-019: service-to-service only. The gateway does not route /internal, and the security
 * configuration lets only the clients in AGENT_REPORT_CLIENTS through.
 */
@RestController
public class InternalPreConsultationReportController {

    public static final String PATH = "/internal/preconsultation-reports";

    private final GetPreConsultationReportUseCase reports;

    public InternalPreConsultationReportController(GetPreConsultationReportUseCase reports) {
        this.reports = reports;
    }

    public record ReportResponse(UUID appointmentId, String reasonForVisit, String source) {
    }

    public record ReportEnvelope(ReportResponse data) {
    }

    @GetMapping(PATH)
    public ReportEnvelope byAppointment(@RequestParam UUID appointmentId) {
        PreConsultationReport report = reports.report(appointmentId);
        return new ReportEnvelope(new ReportResponse(report.appointmentId(), report.reasonForVisit(),
                report.source().name().toLowerCase(Locale.ROOT)));
    }
}
