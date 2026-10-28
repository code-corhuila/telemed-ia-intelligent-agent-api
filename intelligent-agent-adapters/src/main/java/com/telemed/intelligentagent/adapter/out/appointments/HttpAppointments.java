package com.telemed.intelligentagent.adapter.out.appointments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.adapter.observability.CurrentTrace;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.AppointmentStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * appointment-scheduling-api's GET /api/appointments/{id}, called with the
 * patient's own token (token relay): the agent has no privilege of its own
 * over appointments, and that service enforces ownership too. Anything that
 * is not a clear answer fails closed. Each call is a client span of the request
 * (week 11 traces), and the appointment service continues the trace under it.
 */
public class HttpAppointments implements AppointmentsPort {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAppointments.class);

    private final String baseUrl;
    private final Duration timeout;
    private final Supplier<String> callerToken;
    private final HttpClient http;
    private final ObservationRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public HttpAppointments(String baseUrl, Duration timeout, Supplier<String> callerToken) {
        this(baseUrl, timeout, callerToken, ObservationRegistry.NOOP);
    }

    public HttpAppointments(String baseUrl, Duration timeout, Supplier<String> callerToken,
                            ObservationRegistry registry) {
        this.registry = registry;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.callerToken = callerToken;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<Appointment> findById(UUID appointmentId) {
        String token = callerToken.get();
        if (token == null || token.isBlank()) {
            throw unavailable("no caller token to relay");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/appointments/" + appointmentId))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET();
        withTraceContext(builder);
        // With a tracer, starting the observation replaces the traceparent with its own client span.
        RequestReplySenderContext<HttpRequest.Builder, HttpResponse<String>> context =
                new RequestReplySenderContext<>(HttpRequest.Builder::setHeader);
        context.setCarrier(builder);
        context.setRemoteServiceName("appointment-scheduling-api");
        Observation observation = Observation.createNotStarted("agent.appointments.request", () -> context, registry)
                .contextualName("GET /api/appointments/{id}")
                .start();
        try {
            Optional<Appointment> appointment = send(builder.build());
            observation.lowCardinalityKeyValue("outcome", appointment.isPresent() ? "found" : "not_found");
            return appointment;
        } catch (AppointmentVerificationUnavailableException e) {
            observation.lowCardinalityKeyValue("outcome", "unavailable");
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private Optional<Appointment> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        }
        return switch (response.statusCode()) {
            case 200 -> Optional.of(toAppointment(response.body()));
            // Another patient's appointment is answered like a missing one.
            case 403, 404 -> Optional.empty();
            default -> throw unavailable("status " + response.statusCode());
        };
    }

    /**
     * Week 11 (CP3): the appointment service continues this request's trace, with the
     * span this service opened as the parent.
     */
    private static void withTraceContext(HttpRequest.Builder builder) {
        String traceparent = CurrentTrace.traceparent();
        if (traceparent != null) {
            builder.setHeader("traceparent", traceparent);
        }
        String correlationId = CurrentTrace.correlationId();
        if (correlationId != null) {
            builder.header("X-Correlation-Id", correlationId);
        }
    }

    private Appointment toAppointment(String body) {
        try {
            JsonNode node = json.readTree(body);
            return new Appointment(
                    UUID.fromString(node.get("id").asText()),
                    UUID.fromString(node.get("patientId").asText()),
                    UUID.fromString(node.get("professionalId").asText()),
                    AppointmentStatus.valueOf(node.get("status").asText()),
                    Instant.parse(node.get("start").asText()));
        } catch (IOException | RuntimeException unreadable) {
            throw unavailable("unreadable appointment");
        }
    }

    private static AppointmentVerificationUnavailableException unavailable(String reason) {
        LOG.warn("Appointment could not be verified: {}", reason);
        return new AppointmentVerificationUnavailableException("The appointment could not be verified.");
    }
}
