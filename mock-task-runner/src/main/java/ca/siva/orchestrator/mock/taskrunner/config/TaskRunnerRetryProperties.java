package ca.siva.orchestrator.mock.taskrunner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configurable retry settings for the task-runner's downstream HTTP calls.
 *
 * <p>In production, the task-runner retries downstream API calls when the response
 * status code matches one of the configured retryable codes (e.g. 502, 503, 504).
 * The retry count and backoff are configurable per environment.</p>
 *
 * <p>Configuration in application.yml:</p>
 * <pre>
 * task-runner:
 *   retry:
 *     max-attempts: 3
 *     backoff-ms: 1000
 *     retryable-status-codes:
 *       - 502
 *       - 503
 *       - 504
 *       - 429
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "task-runner.retry")
public class TaskRunnerRetryProperties {

    /** Maximum number of retry attempts for downstream calls (default: 3). */
    private int maxAttempts = 3;

    /** Backoff delay in milliseconds between retries (default: 1000ms). */
    private long backoffMs = 1000;

    /** HTTP status codes that trigger a retry (default: 502, 503, 504, 429). */
    private List<Integer> retryableStatusCodes = List.of(502, 503, 504, 429);
}
