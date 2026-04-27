package ca.siva.orchestrator.mock.taskrunner;

import ca.siva.orchestrator.mock.taskrunner.config.TaskRunnerRetryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulates downstream HTTP calls with configurable retry logic.
 *
 * <p>In production, the real task-runner would make actual HTTP calls to downstream
 * systems (diagnostics, notifications, etc.). This mock simulates the call and
 * the retry behavior when the downstream returns a retryable HTTP status code.</p>
 *
 * <p>Retry config comes from {@link TaskRunnerRetryProperties}:</p>
 * <ul>
 *   <li>{@code max-attempts} — how many times to retry (default: 3)</li>
 *   <li>{@code backoff-ms} — delay between retries (default: 1000ms)</li>
 *   <li>{@code retryable-status-codes} — which HTTP codes trigger retry (default: 502, 503, 504, 429)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("local-dev")
public class DownstreamCaller {

    private final TaskRunnerRetryProperties retryProperties;

    /**
     * Simulates a downstream HTTP call with retry.
     *
     * <p>In production, this would be a real HTTP GET/POST to the downstream service.
     * Here we always succeed immediately. The retry logic is implemented so that
     * when the real task-runner replaces this mock, the retry framework is already
     * in place.</p>
     *
     * @param actionName the action being executed (for logging)
     * @param downstreamUrl the URL to call
     * @return the response payload (simulated)
     */
    public Map<String, Object> callWithRetry(String actionName, String downstreamUrl) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                // Simulate the downstream call — in production, this is a real HTTP call
                int statusCode = simulateDownstreamResponse();

                if (statusCode >= 200 && statusCode < 300) {
                    // Success — return a realistic refined response
                    log.debug("Downstream call for {} succeeded on attempt {}", actionName, attempt);
                    return buildRefinedResponse(actionName);
                }

                if (retryProperties.getRetryableStatusCodes().contains(statusCode)) {
                    // Retryable error
                    if (attempt >= retryProperties.getMaxAttempts()) {
                        log.warn("Downstream call for {} failed after {} attempts with status {}",
                                actionName, attempt, statusCode);
                        return Map.of("status", "fail", "httpStatus", statusCode, "attempt", attempt);
                    }
                    log.info("Downstream call for {} returned {} — retrying (attempt {}/{})",
                            actionName, statusCode, attempt, retryProperties.getMaxAttempts());
                    sleep(retryProperties.getBackoffMs());
                } else {
                    // Non-retryable error — fail immediately
                    log.warn("Downstream call for {} failed with non-retryable status {}", actionName, statusCode);
                    return Map.of("status", "fail", "httpStatus", statusCode, "attempt", attempt);
                }

            } catch (Exception e) {
                if (attempt >= retryProperties.getMaxAttempts()) {
                    log.warn("Downstream call for {} threw exception after {} attempts: {}",
                            actionName, attempt, e.getMessage());
                    return Map.of("status", "fail", "error", e.getMessage(), "attempt", attempt);
                }
                log.info("Downstream call for {} threw {} — retrying (attempt {}/{})",
                        actionName, e.getClass().getSimpleName(), attempt, retryProperties.getMaxAttempts());
                sleep(retryProperties.getBackoffMs());
            }
        }
    }

    /**
     * Builds a realistic refined response simulating what the downstream system returns.
     *
     * <p>In production, this is the actual HTTP response body from the downstream
     * service (diagnostics engine, notification gateway, etc.).</p>
     */
    private Map<String, Object> buildRefinedResponse(String actionName) {
        return Map.of(
                "status", "pass",
                "diagnosticSummary", "All checks passed for " + actionName,
                "latencyMs", 245,
                "checksRun", 3,
                "checksPassed", 3,
                "checksFailed", 0
        );
    }

    /**
     * Simulates downstream response — always returns 200 in the mock.
     * In production, replace this with actual HTTP client call.
     */
    private int simulateDownstreamResponse() {
        return 200;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
