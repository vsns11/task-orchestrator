package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Classpath location for DAG YAML definitions. */
@ConfigurationProperties(prefix = "orchestrator.dag")
public record DagProperties(String location) {}
