package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the action-code registry APIs.
 *
 * <p>Basic-auth credentials are NOT on this record — they are shared with the
 * TMF-701 client via {@link FidCredentialsProperties}
 * ({@code FID_USERNAME} / {@code FID_PASSWORD} env vars).</p>
 *
 * @param baseUrl            base URL of the action registry service
 * @param actionCodesPath    URI path for action codes endpoint (default: /action-codes)
 * @param dcxActionCodesPath URI path for DCX action codes endpoint (default: /dcx-action-codes)
 * @param reloadCron         cron expression for periodic reload (default: every 24 hours at 2 AM)
 */
@ConfigurationProperties(prefix = "orchestrator.actionregistry")
public record ActionRegistryProperties(
        String baseUrl,
        String actionCodesPath,
        String dcxActionCodesPath,
        String reloadCron
) {}
