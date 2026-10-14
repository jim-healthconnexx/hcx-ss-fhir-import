package com.healthconnexx.hcxssfhirimport.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HDC-309: Resolves the CloudWatch log_stream name for this app's current run, the same way
 * as HDC-305/HDC-308 — via the ECS Task Metadata Endpoint v4, falling back to "local" when
 * not running under ECS (or on any resolution failure). Resolved once and cached for the
 * life of the run since it cannot change mid-task.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogStreamService {

    private static final String LOCAL_FALLBACK = "local";
    private static final String METADATA_URI_ENV_VAR = "ECS_CONTAINER_METADATA_URI_V4";

    private final ObjectMapper objectMapper;

    private volatile String cachedLogStream;

    public String getLogStream() {
        String resolved = cachedLogStream;
        if (resolved == null) {
            synchronized (this) {
                resolved = cachedLogStream;
                if (resolved == null) {
                    resolved = resolveLogStream();
                    cachedLogStream = resolved;
                }
            }
        }
        return resolved;
    }

    private String resolveLogStream() {
        String metadataUri = System.getenv(METADATA_URI_ENV_VAR);
        if (metadataUri == null || metadataUri.isBlank()) {
            log.debug("HDC-309: {} not set — using log_stream fallback '{}'", METADATA_URI_ENV_VAR, LOCAL_FALLBACK);
            return LOCAL_FALLBACK;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metadataUri))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode awsLogsStream = root.path("LogOptions").path("awslogs-stream");
            if (awsLogsStream.isTextual()) {
                String logStream = awsLogsStream.asText();
                log.debug("HDC-309: Resolved log_stream='{}' from ECS task metadata", logStream);
                return logStream;
            }
            log.warn("HDC-309: ECS task metadata response missing LogOptions.awslogs-stream — using fallback '{}'",
                    LOCAL_FALLBACK);
        } catch (Exception e) {
            log.warn("HDC-309: Failed to resolve log_stream from ECS task metadata — using fallback '{}': {}",
                    LOCAL_FALLBACK, e.getMessage());
        }
        return LOCAL_FALLBACK;
    }
}
