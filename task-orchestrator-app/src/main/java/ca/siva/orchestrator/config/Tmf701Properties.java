package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the TMF-701 processFlow API.
 *
 * @param baseUrl         base URL of the TMF-701 service (e.g. {@code http://tmf701/api/v4})
 * @param processFlowPath URI template for the processFlow resource, with {@code {id}} placeholder
 *                        (e.g. {@code /processFlow/{id}})
 */
@ConfigurationProperties(prefix = "orchestrator.tmf701")
public record Tmf701Properties(String baseUrl, String processFlowPath) {}
