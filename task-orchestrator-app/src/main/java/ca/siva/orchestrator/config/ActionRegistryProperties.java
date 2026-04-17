package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for the action-code registry APIs.
 *
 * <p>Basic-auth credentials are NOT on this record — they are shared with the
 * TMF-701 client via {@link FidCredentialsProperties}
 * ({@code FID_USERNAME} / {@code FID_PASSWORD} env vars).</p>
 *
 * @param baseUrl              base URL of the action registry service
 * @param actionCodesPath      URI path for action codes endpoint (default: /action-codes)
 * @param dcxActionCodesPath   URI path for DCX action codes endpoint (default: /dcx-action-codes)
 * @param reloadCron           cron expression for periodic reload (default: every 24 hours at 2 AM)
 * @param requiredActionNames  allowlist of {@code actionName} values that MUST be
 *                             present in the action-code registry at boot time;
 *                             {@code ActionRegistry} fails fast (System.exit) on
 *                             the initial load when any entry is missing. Ops can
 *                             add or remove entries without a code change by
 *                             editing {@code orchestrator.actionregistry.required-action-names}
 *                             in the active YAML profile.
 */
@ConfigurationProperties(prefix = "orchestrator.actionregistry")
public record ActionRegistryProperties(
        String baseUrl,
        String actionCodesPath,
        String dcxActionCodesPath,
        String reloadCron,
        List<String> requiredActionNames
) {
    public ActionRegistryProperties {
        // Normalise null → empty list so callers never need to null-check.
        requiredActionNames = requiredActionNames == null
                ? Collections.emptyList()
                : List.copyOf(requiredActionNames);
    }
}
