package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.in.GetPreConsultationReportUseCase;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;

import java.util.UUID;

public class PreConsultationReportService implements GetPreConsultationReportUseCase {

    public PreConsultationReportService(SummaryRepository summaries, AgentModelPort model) {
    }

    @Override
    public PreConsultationReport report(UUID appointmentId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
