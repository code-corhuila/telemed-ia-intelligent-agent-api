package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.adapter.out.appointments.HttpAppointments;
import com.telemed.intelligentagent.adapter.out.appointments.UnverifiedAppointments;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Without APPOINTMENTS_BASE_URL nothing can start (fail closed); with it, appointments are verified there. */
@Configuration(proxyBeanMethods = false)
public class AppointmentsConfiguration {

    @Bean
    AppointmentsPort appointments(AgentProperties properties) {
        AgentProperties.Appointments appointments = properties.appointments();
        if (appointments.baseUrl() == null || appointments.baseUrl().isBlank()) {
            return new UnverifiedAppointments();
        }
        return new HttpAppointments(appointments.baseUrl(), appointments.timeout(),
                AppointmentsConfiguration::currentPatientToken);
    }

    /** The token of the request being served; never a token of the agent's own. */
    private static String currentPatientToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null;
    }
}
