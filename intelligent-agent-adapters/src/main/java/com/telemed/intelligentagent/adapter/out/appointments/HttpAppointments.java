package com.telemed.intelligentagent.adapter.out.appointments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import com.telemed.intelligentagent.domain.model.Appointment;
import com.telemed.intelligentagent.domain.model.AppointmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
 * is not a clear answer fails closed.
 */
public class HttpAppointments implements AppointmentsPort {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAppointments.class);

    private final String baseUrl;
    private final Duration timeout;
    private final Supplier<String> callerToken;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public HttpAppointments(String baseUrl, Duration timeout, Supplier<String> callerToken) {
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
        HttpRequest request = withTraceContext(builder).build();
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
     * span this service opened (TraceContextFilter's MDC) as the parent.
     */
    private static HttpRequest.Builder withTraceContext(HttpRequest.Builder builder) {
        String traceId = MDC.get("trace_id");
        String spanId = MDC.get("span_id");
        if (traceId != null && spanId != null) {
            builder.header("traceparent", "00-" + traceId + "-" + spanId + "-01");
        }
        String correlationId = MDC.get("correlation_id");
        if (correlationId != null) {
            builder.header("X-Correlation-Id", correlationId);
        }
        return builder;
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
