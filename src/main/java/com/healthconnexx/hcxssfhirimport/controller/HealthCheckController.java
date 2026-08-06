package com.healthconnexx.hcxssfhirimport.controller;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

// HDC-221: Simple health indicator — Spring Actuator /actuator/health will aggregate this.
@Component
public class HealthCheckController implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up().build();
    }
}
