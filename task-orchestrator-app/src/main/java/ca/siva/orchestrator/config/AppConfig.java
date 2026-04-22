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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Map;

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
        ActionRegistryProperties.class,
        FidCredentialsProperties.class,
        HttpClientProperties.class
})
public class AppConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Shared {@link RestClient.Builder} pinned to the JDK 11+ HTTP client.
     *
     * <p><b>Why pin to {@link JdkClientHttpRequestFactory}?</b> Spring Boot's
     * {@code ClientHttpRequestFactories.get(DEFAULTS)} auto-selects a factory
     * based on what's on the classpath, and on environments where only the
     * servlet container is present it falls back to
     * {@code SimpleClientHttpRequestFactory} — which wraps
     * {@link java.net.HttpURLConnection} and refuses the {@code PATCH} method
     * with "invalid HTTP method: PATCH". TMF-701 is PATCH-heavy (merge-patch
     * on {@code relatedEntity} and on {@code state}), so we pin to the JDK
     * HTTP client explicitly. It has native support for every HTTP method,
     * preserves custom headers like
     * {@code Content-Type: application/merge-patch+json} verbatim, and ships
     * in the JDK so there's no new transitive dep to manage.</p>
     *
     * <p>Both connect and read timeouts are sourced from
     * {@link HttpClientProperties} (keys {@code orchestrator.http.connect-timeout}
     * and {@code orchestrator.http.read-timeout}). When the operator does not
     * declare either, the record applies its 1200-second default.</p>
     */
    @Bean
    public RestClient.Builder restClientBuilder(HttpClientProperties httpProps) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .connectTimeout(httpProps.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkHttpClient);
        factory.setReadTimeout(httpProps.readTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * Retry policy for outbound HTTP calls (TMF-701, action registry).
     *
     * <p><b>What we retry:</b></p>
     * <ul>
     *   <li>{@link ResourceAccessException} — connect/read timeouts and any
     *       other transport failure. Almost always transient.</li>
     *   <li>{@link HttpServerErrorException} — any 5xx response. The server
     *       is broken, restarting, or overloaded; a retry has a real chance
     *       of succeeding.</li>
     *   <li>Selected 4xx responses that are transient by spec:
     *       {@code 408 Request Timeout} and {@code 429 Too Many Requests}.</li>
     * </ul>
     *
     * <p><b>What we do NOT retry:</b> other 4xx (malformed request, bad
     * credentials, not found) — the response would be identical on the next
     * attempt, so retrying just wastes latency budget.</p>
     *
     * <p><b>Budget:</b> Every knob — attempt count, backoff initial / max /
     * multiplier — is sourced from {@link HttpClientProperties.Retry}
     * (yml prefix {@code orchestrator.http.retry}). Defaults produce 3
     * attempts total (1 initial + 2 retries) with 500 ms → 4 s exponential
     * waits, i.e. ~1.5 s of total inter-attempt delay before the caller sees
     * the failure.</p>
     */
    @Bean
    public RetryTemplate httpCallRetryTemplate(HttpClientProperties httpProps) {
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(
                ResourceAccessException.class, true,
                HttpServerErrorException.class, true,
                TransientClientError.class,     true
        );
        HttpClientProperties.Retry retry = httpProps.retry();

        // SimpleRetryPolicy's first arg is TOTAL attempts (Spring Retry
        // semantics), not retry count — a value of 3 here means 1 initial
        // call + 2 retries, matching how the operator will read the yml key
        // orchestrator.http.retry.max-attempts.
        SimpleRetryPolicy policy = new SimpleRetryPolicy(retry.maxAttempts(), retryable, true);

        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(retry.initialBackoff().toMillis());
        backoff.setMultiplier(retry.multiplier());
        backoff.setMaxInterval(retry.maxBackoff().toMillis());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(policy);
        template.setBackOffPolicy(backoff);
        return template;
    }

    /**
     * Marker exception used to route transient 4xx responses (e.g. 408, 429)
     * into the same retry path as {@link HttpServerErrorException}. The Tmf701/
     * ActionRegistry clients translate a matching {@link RestClientResponseException}
     * into this type (based on {@link HttpClientProperties#isRetryableStatus(int)})
     * before re-throwing inside the retry callback.
     */
    public static class TransientClientError extends RuntimeException {
        public TransientClientError(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
