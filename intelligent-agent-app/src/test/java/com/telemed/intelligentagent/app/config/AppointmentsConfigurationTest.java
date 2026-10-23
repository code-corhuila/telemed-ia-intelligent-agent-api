package com.telemed.intelligentagent.app.config;

import com.telemed.intelligentagent.adapter.out.appointments.HttpAppointments;
import com.telemed.intelligentagent.adapter.out.appointments.UnverifiedAppointments;
import com.telemed.intelligentagent.application.port.out.AppointmentsPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Without the appointment service's address the agent fails closed; with it, it asks that service. */
class AppointmentsConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(AgentModelConfiguration.class, AppointmentsConfiguration.class);

    @Test
    void failsClosedWithoutTheAppointmentServiceAddress() {
        context.run(app -> assertThat(app.getBean(AppointmentsPort.class)).isInstanceOf(UnverifiedAppointments.class));
    }

    @Test
    void asksTheAppointmentServiceWhenItsAddressIsSet() {
        context.withPropertyValues("agent.appointments.base-url=http://appointment-scheduling-api:8080")
                .run(app -> assertThat(app.getBean(AppointmentsPort.class)).isInstanceOf(HttpAppointments.class));
    }
}
