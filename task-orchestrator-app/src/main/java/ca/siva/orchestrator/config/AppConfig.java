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
     * <p><b>Budget:</b> 1 initial attempt + 3 retries = up to 4 attempts.
     * Backoff starts at 500 ms and doubles up to 4 s, so the worst-case
     * end-to-end wait is ~0.5 + 1 + 2 + 4 ≈ 7.5 s before the call finally
     * fails through to the caller (which today logs and swallows).</p>
     */
    @Bean
    public RetryTemplate httpCallRetryTemplate() {
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(
                ResourceAccessException.class, true,
                HttpServerErrorException.class, true,
                TransientClientError.class,     true
        );
        SimpleRetryPolicy policy = new SimpleRetryPolicy(4, retryable, true);

        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(4_000);

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
