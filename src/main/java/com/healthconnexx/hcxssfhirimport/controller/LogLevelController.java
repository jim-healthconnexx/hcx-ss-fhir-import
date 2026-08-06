package com.healthconnexx.hcxssfhirimport.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// HDC-221: Runtime log-level override — delegates to Spring Actuator /actuator/loggers.
// Provided as a convenience REST endpoint for manual log-level adjustment.
@RestController
@RequestMapping("/api/v1/log-level")
@RequiredArgsConstructor
public class LogLevelController {

    private final LoggersEndpoint loggersEndpoint;

    @PostMapping
    public ResponseEntity<Void> setLogLevel(
            @RequestParam String logger,
            @RequestParam String level) {
        loggersEndpoint.configureLogLevel(logger,
                org.springframework.boot.logging.LogLevel.valueOf(level.toUpperCase()));
        return ResponseEntity.noContent().build();
    }
}
