package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * HTTP-client-wide tuning knobs shared by every outbound REST caller
 * (TMF-701, action registry). Centralising these removes per-client drift
 * and makes both the timeout and retry budgets visible in yml instead of
 * buried in Java literals.
 *
 * <h3>YAML keys (prefix {@code orchestrator.http})</h3>
 * <pre>
 * orchestrator:
 *   http:
 *     connect-timeout: 120s                                  # default: 120 s
 *     read-timeout: 120s                                     # default: 120 s
 *     retryable-statuses: [408, 429, 500, 502, 503, 504]     # default set
 *     retry:
 *       max-attempts: 3                                      # total attempts (1 initial + 2 retries)
 *       initial-backoff: 500ms                               # first wait between retries
 *       max-backoff: 4s                                      # cap on the growing wait
 *       multiplier: 2.0                                      # exponential growth factor
 * </pre>
 *
 * <h3>Defaults</h3>
 * <ul>
 *   <li>{@code connectTimeout} / {@code readTimeout} default to
 *       {@link #DEFAULT_TIMEOUT} ({@value #DEFAULT_TIMEOUT_SECONDS} s).</li>
 *   <li>{@code retryableStatuses} defaults to the RFC-transient 4xx pair
 *       plus the common 5xx family — see {@link #DEFAULT_RETRYABLE_STATUSES}.</li>
 *   <li>{@code retry.maxAttempts} defaults to {@code 3} — Spring Retry counts
 *       TOTAL attempts, so this is 1 initial call + 2 retries.</li>
 *   <li>{@code retry.initialBackoff} / {@code retry.maxBackoff} /
 *       {@code retry.multiplier} default to {@code 500 ms} / {@code 4 s} /
 *       {@code 2.0}.</li>
 * </ul>
 *
 * <p>Durations follow Spring Boot's standard parser (supports {@code 30s},
 * {@code PT5M}, {@code 1500ms}, etc.).</p>
 */
@ConfigurationProperties(prefix = "orchestrator.http")
public record HttpClientProperties(
        // @DurationUnit(SECONDS) tells Spring Boot's binder that a bare
        // number (e.g. `connect-timeout: 120`) should be interpreted as
        // SECONDS, not the library default of milliseconds. Values with an
        // explicit unit — `500ms`, `PT5M`, `30s` — still win over the
        // annotation. This matches operator expectations and prevents
        // accidental sub-second timeouts from a unitless yml value.
        @DurationUnit(ChronoUnit.SECONDS) Duration connectTimeout,
        @DurationUnit(ChronoUnit.SECONDS) Duration readTimeout,
        List<Integer> retryableStatuses,
        Retry retry
) {

    static final long DEFAULT_TIMEOUT_SECONDS = 120L;

    /** Fallback when the operator hasn't declared a timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);

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
     * operator left unset. Also defensively copies the status list so a
     * mutable yml-sourced list can't be swapped under us at runtime.
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
            retryableStatuses = List.copyOf(retryableStatuses);
        }
        if (retry == null) {
            retry = Retry.defaults();
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

    /**
     * Nested config for the shared {@code RetryTemplate} bean
     * ({@code AppConfig.httpCallRetryTemplate}).
     *
     * <h4>Semantics</h4>
     * <ul>
     *   <li>{@code maxAttempts} is TOTAL attempts (Spring Retry semantics),
     *       NOT the retry count. {@code maxAttempts=3} ⇒ 1 initial call +
     *       2 retries.</li>
     *   <li>Wait between attempts grows exponentially:
     *       {@code delay_n = min(initialBackoff * multiplier^(n-1), maxBackoff)}.</li>
     * </ul>
     */
    public record Retry(
            Integer maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Double multiplier
    ) {

        /** 1 initial + 2 retries = 3 attempts total. */
        public static final int DEFAULT_MAX_ATTEMPTS = 3;

        /** First inter-attempt wait. */
        public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);

        /** Upper bound on the growing wait. */
        public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(4);

        /** Growth factor — 2.0 doubles each time. */
        public static final double DEFAULT_MULTIPLIER = 2.0;

        public Retry {
            if (maxAttempts == null || maxAttempts < 1) {
                maxAttempts = DEFAULT_MAX_ATTEMPTS;
            }
            if (initialBackoff == null) {
                initialBackoff = DEFAULT_INITIAL_BACKOFF;
            }
            if (maxBackoff == null) {
                maxBackoff = DEFAULT_MAX_BACKOFF;
            }
            if (multiplier == null || multiplier <= 1.0) {
                // A multiplier of 1.0 or less would either produce a flat or
                // shrinking backoff — almost always a misconfiguration. Snap
                // back to the documented default rather than silently
                // disabling exponential growth.
                multiplier = DEFAULT_MULTIPLIER;
            }
        }

        /** Fully-defaulted Retry, used when the {@code retry:} block is absent. */
        public static Retry defaults() {
            return new Retry(null, null, null, null);
        }
    }
}
