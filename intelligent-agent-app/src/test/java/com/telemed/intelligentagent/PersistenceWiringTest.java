package com.telemed.intelligentagent;

import com.telemed.intelligentagent.application.port.in.FinishPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.GetPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.in.SendMessageUseCase;
import com.telemed.intelligentagent.application.port.in.StartPreConsultationUseCase;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.application.port.out.ConversationRepository;
import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.application.port.out.UnitOfWork;
import com.telemed.intelligentagent.domain.model.Conversation;
import com.telemed.intelligentagent.domain.model.ConversationStatus;
import com.telemed.intelligentagent.domain.model.Message;
import com.telemed.intelligentagent.domain.model.Sender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The use cases running on agent-db through the app's wiring, with the scripted model. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"}
)
class PersistenceWiringTest {

    private static final UUID PATIENT = UUID.randomUUID();

    @Autowired
    private StartPreConsultationUseCase start;
    @Autowired
    private SendMessageUseCase send;
    @Autowired
    private GetPreConsultationUseCase get;
    @Autowired
    private FinishPreConsultationUseCase finish;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private UnitOfWork unitOfWork;
    @Autowired
    private MongoTemplate mongo;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        TestIdentityKeys.register(registry);
        TestMongo.register(registry);
    }

    @BeforeEach
    void emptyAgentDb() {
        mongo.getDb().drop();
    }

    @Test
    void refusesToStartUntilAppointmentsCanBeVerified() {
        // First the appointment, then the pre-consultation: without a way to verify it, nothing starts.
        assertThatThrownBy(() -> start.start(PATIENT, UUID.randomUUID()))
                .isInstanceOf(AppointmentVerificationUnavailableException.class);
        assertThat(mongo.getCollectionNames()).doesNotContain("conversations");
    }

    @Test
    void runsAWholePreConsultationOnAgentDb() {
        Conversation conversation = Conversation.start(PATIENT, UUID.randomUUID(), 8, Instant.now());
        conversations.save(conversation);
        messages.save(Message.of(conversation.id(), Sender.AGENT, "opening-" + conversation.id(), "¿Qué te trae?",
                Instant.now()));

        List.of("Me duele la cabeza", "Desde hace dos días", "Un 6 de 10", "Tengo migraña")
                .forEach(text -> send.send(PATIENT, conversation.id(), UUID.randomUUID().toString(), text));

        GetPreConsultationUseCase.PreConsultationView view = get.get(PATIENT, conversation.id());
        assertThat(view.conversation().status()).isEqualTo(ConversationStatus.FINISHED);
        assertThat(view.messages()).hasSize(9);
        assertThat(view.summary()).hasValueSatisfying(
                summary -> assertThat(summary.consultationReason()).isEqualTo("Me duele la cabeza"));
        assertThat(finish.finish(PATIENT, conversation.id())).isEqualTo(view.summary().orElseThrow());
        assertThat(mongo.getCollection("outbox_events").countDocuments()).isEqualTo(1);
    }

    @Test
    void commitsAUnitOfWorkAsAMongoTransaction() {
        Message message = Message.of(UUID.randomUUID(), Sender.AGENT, "opening-key-01", "Hola", Instant.now());
        mongo.createCollection("messages");

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            messages.save(message);
            throw new IllegalStateException("a later write failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongo.getCollection("messages").countDocuments()).isZero();
    }
}
