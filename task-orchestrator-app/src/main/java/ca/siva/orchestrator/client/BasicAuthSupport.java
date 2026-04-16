package ca.siva.orchestrator.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Tiny helper for computing the HTTP {@code Authorization: Basic …} header
 * from a username/password pair. Centralized here so the two HTTP clients
 * ({@code Tmf701Client}, {@code ActionRegistry}) can't drift in how they
 * encode credentials or decide when to send the header.
 *
 * <p>Credentials themselves are resolved by Spring via
 * {@code FidCredentialsProperties} (bound to {@code orchestrator.fid.*} in
 * yml, which references the {@code FID_USERNAME} / {@code FID_PASSWORD} env
 * vars). This class deliberately does not read the environment directly —
 * callers pass the resolved values in.</p>
 */
public final class BasicAuthSupport {

    private BasicAuthSupport() {}

    /**
     * Returns the {@code "Basic <base64(user:pass)>"} header value, or
     * {@code null} if either input is blank. A {@code null} return tells the
     * caller to skip setting the {@code Authorization} header.
     */
    public static String header(String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            return null;
        }
        String raw = username + ":" + password;
        String encoded = Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /** True if the string is null, empty, or whitespace-only. */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
