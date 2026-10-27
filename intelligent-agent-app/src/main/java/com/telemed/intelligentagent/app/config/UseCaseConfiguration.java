package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.ReadSummaryForProfessionalUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AgentModelPort;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.IdempotencyRecords;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import com.telemed.intelligentagent.application.service.FinishPreConsultationService;
import com.telemed.intelligentagent.application.service.GetPreConsultationService;
import com.telemed.intelligentagent.application.service.PreConsultationSummarizer;
import com.telemed.intelligentagent.application.service.ReadSummaryForProfessionalService;
import com.telemed.intelligentagent.application.service.SendMessageService;
import com.telemed.intelligentagent.application.service.PausableStartPreConsultation;
import com.telemed.intelligentagent.application.service.StartPreConsultationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The core's use cases, assembled from their ports; the core itself knows nothing of Spring. */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfiguration {

    @Bean
    PreConsultationSummarizer summarizer(AgentModelPort model) {
        return new PreConsultationSummarizer(model);
    }

    @Bean
    StartPreConsultationUseCase startPreConsultation(AppointmentsPort appointments,
                                                     ConversationRepository conversations,
                                                     MessageRepository messages, IdempotencyRecords idempotency,
                                                     UnitOfWork unitOfWork, AgentProperties properties,
                                                     Clock clock) {
        return new PausableStartPreConsultation(
                new StartPreConsultationService(appointments, conversations, messages, idempotency, unitOfWork,
                        properties.questionLimit(), clock),
                properties::preconsultationsPaused);
    }

    @Bean
    SendMessageUseCase sendMessage(ConversationRepository conversations, MessageRepository messages,
                                   SummaryRepository summaries, PreConsultationSummarizer summarizer,
                                   AgentModelPort model, UnitOfWork unitOfWork, AgentProperties properties,
                                   Clock clock) {
        return new SendMessageService(conversations, messages, summaries, summarizer, model,
                properties.minimumQuestions(), unitOfWork, clock);
    }

    @Bean
    FinishPreConsultationUseCase finishPreConsultation(ConversationRepository conversations,
                                                       MessageRepository messages, SummaryRepository summaries,
                                                       PreConsultationSummarizer summarizer, UnitOfWork unitOfWork,
                                                       Clock clock) {
        return new FinishPreConsultationService(conversations, messages, summaries, summarizer, unitOfWork, clock);
    }

    @Bean
    ReadSummaryForProfessionalUseCase readSummaryForProfessional(AppointmentsPort appointments,
                                                                 SummaryRepository summaries) {
        return new ReadSummaryForProfessionalService(appointments, summaries);
    }

    @Bean
    GetPreConsultationUseCase getPreConsultation(ConversationRepository conversations,
                                                 MessageRepository messages) {
        return new GetPreConsultationService(conversations, messages);
    }
}
