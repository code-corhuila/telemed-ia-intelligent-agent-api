package com.telemed.intelligentagent.adapter.out.appointments;

import com.sun.net.httpserver.HttpServer;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.AppointmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** appointment-scheduling-api's GET /api/appointments/{id}, emulated by a local server. */
class HttpAppointmentsTest {

    private static final String PATIENT_TOKEN = "patient.jwt.token";
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID PROFESSIONAL = UUID.randomUUID();

    private final List<String> paths = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> traceparents = new ArrayList<>();
    private final List<String> correlationIds = new ArrayList<>();
    private int status = 200;
    private String body;
    private long delayMillis;
    private HttpServer server;
    private HttpAppointments appointments;

    @BeforeEach
    void startFakeAppointmentService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointments/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            traceparents.add(exchange.getRequestHeaders().getFirst("traceparent"));
            correlationIds.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = (body == null ? "{\"detail\":\"x\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        appointments = new HttpAppointments("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(500), () -> PATIENT_TOKEN);
    }

    @AfterEach
    void stopFakeAppointmentService() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void readsTheAppointmentWithThePatientsOwnToken() {
        body = appointmentJson("RESCHEDULED");

        Appointment appointment = appointments.findById(APPOINTMENT).orElseThrow();

        // Token relay: the agent holds no privilege of its own over appointments.
        assertThat(authorizations).containsExactly("Bearer " + PATIENT_TOKEN);
        assertThat(paths).containsExactly("/api/appointments/" + APPOINTMENT);
        assertThat(appointment).isEqualTo(new Appointment(APPOINTMENT, PATIENT, PROFESSIONAL, AppointmentStatus.RESCHEDULED,
                Instant.parse("2026-11-03T14:00:00Z")));
    }

    @Test
    void continuesTheRequestsTraceInTheAppointmentService() {
        // What TraceContextFilter leaves in the MDC for the request being served.
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        MDC.put("correlation_id", "corr-0001");
        body = appointmentJson("CONFIRMED");

        appointments.findById(APPOINTMENT);

        // Week 11 (CP3): same trace_id downstream, with this service's span as the parent.
        assertThat(traceparents).containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(correlationIds).containsExactly("corr-0001");
    }

    @Test
    void sendsNoTraceHeadersOutsideATracedRequest() {
        body = appointmentJson("CONFIRMED");

        appointments.findById(APPOINTMENT);

        assertThat(traceparents).containsOnlyNulls().hasSize(1);
        assertThat(correlationIds).containsOnlyNulls().hasSize(1);
    }

    @Test
    void treatsAMissingOrForeignAppointmentAsNotFound() {
        status = 404;
        assertThat(appointments.findById(APPOINTMENT)).isEmpty();

        status = 403;
        assertThat(appointments.findById(APPOINTMENT)).isEmpty();
    }

    @Test
    void failsClosedWhenTheAppointmentCannotBeVerified() {
        status = 500;
        assertUnavailable();

        status = 401;
        assertUnavailable();

        status = 200;
        body = appointmentJson("SOMETHING_NEW");
        assertUnavailable();

        body = "not json";
        assertUnavailable();

        body = appointmentJson("CONFIRMED");
        delayMillis = 1500;
        assertUnavailable();
    }

    @Test
    void failsClosedWithoutACallerToken() {
        HttpAppointments withoutToken = new HttpAppointments("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(500), () -> null);

        assertThatThrownBy(() -> withoutToken.findById(APPOINTMENT))
                .isInstanceOf(AppointmentVerificationUnavailableException.class);
        assertThat(paths).isEmpty();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> appointments.findById(APPOINTMENT))
                .isInstanceOf(AppointmentVerificationUnavailableException.class);
    }

    private static String appointmentJson(String status) {
        return """
                {"id":"%s","patientId":"%s","professionalId":"%s","preconsultationSummaryId":null,
                 "postSummaryId":null,"start":"2026-11-03T14:00:00Z","end":"2026-11-03T14:30:00Z",
                 "status":"%s","cancellationReason":null,"createdAt":"2026-10-22T15:00:00Z",
                 "updatedAt":"2026-10-22T15:00:00Z"}""".formatted(APPOINTMENT, PATIENT, PROFESSIONAL, status);
    }
}
