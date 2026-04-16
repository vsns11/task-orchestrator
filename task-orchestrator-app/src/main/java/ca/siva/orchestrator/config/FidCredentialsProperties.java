package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared FID (Federated Identity) credentials used for every outbound HTTP call
 * the orchestrator makes — today the action-code registry
 * ({@code /action-codes}, {@code /dcx-action-codes}) and TMF-701
 * ({@code /processFlow/{id}}). Both APIs accept the same FID service account,
 * so we bind one pair here instead of duplicating user/pass per service.
 *
 * <p>Bound to {@code orchestrator.fid.*} in
 * {@code application-orchestration.yml}. The yml entries themselves reference
 * environment variables via Spring's {@code ${…}} placeholders, so the secret
 * values live in the process environment, not in the repo:</p>
 *
 * <pre>
 * orchestrator:
 *   fid:
 *     username: ${FID_USERNAME:}
 *     password: ${FID_PASSWORD:}
 * </pre>
 *
 * <p>Both values are optional — when either is blank the clients skip the
 * {@code Authorization} header entirely (local-dev mocks and integration
 * tests run this way).</p>
 *
 * @param username Basic-auth username (blank = no Authorization header sent)
 * @param password Basic-auth password (blank = no Authorization header sent)
 */
@ConfigurationProperties(prefix = "orchestrator.fid")
public record FidCredentialsProperties(String username, String password) {}
