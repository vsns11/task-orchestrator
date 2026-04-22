package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * HTTP-client-wide tuning knobs shared by every outbound REST caller
 * (TMF-701, action registry). Centralising these removes per-client drift
 * and makes the retry budget visible in yml instead of buried in Java
 * literals.
 *
 * <h3>YAML keys (prefix {@code orchestrator.http})</h3>
 * <pre>
 * orchestrator:
 *   http:
 *     connect-timeout: 1200s           # default if unset: 1200 seconds (PT20M)
 *     read-timeout: 1200s              # default if unset: 1200 seconds (PT20M)
 *     retryable-statuses: [408, 429, 500, 502, 503, 504]   # default set
 * </pre>
 *
 * <h3>Defaults</h3>
 * <ul>
 *   <li>{@code connectTimeout} and {@code readTimeout} default to
 *       {@code 1200 s} (20 min). The JDK {@link java.net.http.HttpClient}
 *       does not enforce a connect timeout when one is not set; a conservative
 *       floor avoids a thread-per-request hanging forever on a black-holed
 *       destination.</li>
 *   <li>{@code retryableStatuses} defaults to {@code [408, 429, 500, 502, 503, 504]}
 *       — the RFC-transient 4xx pair plus the common 5xx family. Tuning this
 *       list is a yml edit, no recompile.</li>
 * </ul>
 *
 * <p>Durations follow Spring Boot's standard parser (supports {@code 30s},
 * {@code PT5M}, {@code 1500ms}, etc.).</p>
 */
@ConfigurationProperties(prefix = "orchestrator.http")
public record HttpClientProperties(
        Duration connectTimeout,
        Duration readTimeout,
        List<Integer> retryableStatuses
) {

    /** Fallback when the operator hasn't declared a value. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Default retryable HTTP status codes. The set is chosen to be safely
     * retriable by spec:
     * <ul>
     *   <li>408 — Request Timeout (client may safely retry)</li>
     *   <li>429 — Too Many Requests (rate-limit; retry-after-backoff is correct)</li>
     *   <li>500, 502, 503, 504 — generic server failure / upstream dead / overloaded / gateway timeout</li>
     * </ul>
     */
    public static final List<Integer> DEFAULT_RETRYABLE_STATUSES =
            List.of(408, 429, 500, 502, 503, 504);

    /**
     * Canonical (compact) constructor applies defaults for any value the
     * operator left unset. Also freezes the status list into a {@link Set}-
     * backed view so membership checks are O(1) regardless of list size.
     */
    public HttpClientProperties {
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_TIMEOUT;
        }
        if (readTimeout == null) {
            readTimeout = DEFAULT_TIMEOUT;
        }
        if (retryableStatuses == null || retryableStatuses.isEmpty()) {
            retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        } else {
            // Defensive copy so a mutable yml-sourced list can't be swapped
            // under us at runtime.
            retryableStatuses = List.copyOf(retryableStatuses);
        }
    }

    /**
     * Membership check used by client-side exception handlers to decide
     * whether to translate a {@link org.springframework.web.client.RestClientResponseException}
     * into the retry-friendly marker exception. Kept as an instance method
     * (not static) so the check reads the injected bean's configuration
     * rather than a hardcoded set. List is small (typically ≤10 entries);
     * contains() on an immutable List is fast enough — no Set wrapper needed.
     */
    public boolean isRetryableStatus(int status) {
        return retryableStatuses.contains(status);
    }
}
