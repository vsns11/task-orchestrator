package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base URL for the TMF-701 processFlow API. */
@ConfigurationProperties(prefix = "orchestrator.tmf701")
public record Tmf701Properties(String baseUrl) {}
