package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.adapter.in.http.TraceIds;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.adapter.observability.ObservedUnitOfWork;
import com.telemed.intelligentagent.adapter.out.persistence.mongo.MongoConversationRepository;
import com.telemed.intelligentagent.adapter.out.persistence.mongo.MongoIdempotencyRecords;
import com.telemed.intelligentagent.adapter.out.persistence.mongo.MongoMessageRepository;
import com.telemed.intelligentagent.adapter.out.persistence.mongo.MongoSummaryRepository;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.IdempotencyRecords;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.SummaryRepository;
import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/** agent-db through MONGODB_URI (intelligent_agent_app user); a unit of work is a Mongo transaction. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    @Bean
    TransactionTemplate transactionTemplate(MongoTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    // The span wraps the transaction, not each command: the outbox gauge's scrapes open no traces of their own.
    @Bean
    UnitOfWork unitOfWork(TransactionTemplate transaction, ObjectProvider<ObservationRegistry> registry) {
        return new ObservedUnitOfWork(work -> transaction.executeWithoutResult(status -> work.run()),
                registry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    ConversationRepository conversationRepository(MongoTemplate mongo, Clock clock) {
        return new MongoConversationRepository(mongo, clock);
    }

    // Long enough for any client retry; agent-db's TTL index removes the record afterwards.
    @Bean
    IdempotencyRecords idempotencyRecords(MongoTemplate mongo) {
        return new MongoIdempotencyRecords(mongo, Duration.ofHours(24));
    }

    @Bean
    MessageRepository messageRepository(MongoTemplate mongo) {
        return new MongoMessageRepository(mongo);
    }

    // Each outbox event carries the X-Correlation-Id and the W3C trace context of the request that produced it.
    @Bean
    SummaryRepository summaryRepository(MongoTemplate mongo, TransactionTemplate transaction, Clock clock) {
        return new MongoSummaryRepository(mongo, transaction, clock, TraceIds::currentAsUuid,
                CurrentTrace::traceparent);
    }
}
