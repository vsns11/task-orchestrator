package ca.siva.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Application-wide bean configuration.
 * Registers all {@code @ConfigurationProperties} records and provides
 * a pre-configured {@link ObjectMapper} with Java time support.
 */
@Configuration
@EnableConfigurationProperties({
        TopicsProperties.class,
        DagProperties.class,
        Tmf701Properties.class,
        ActionRegistryProperties.class
})
public class AppConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Write Instant as "2026-04-14T20:14:59.918Z" not 1776197699.918
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
