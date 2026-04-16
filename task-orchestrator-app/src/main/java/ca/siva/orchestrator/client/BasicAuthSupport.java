package ca.siva.orchestrator.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Tiny helper for computing the HTTP {@code Authorization: Basic …} header
 * from a username/password pair. Centralized here so the two HTTP clients
 * ({@code Tmf701Client}, {@code ActionRegistry}) can't drift in how they
 * encode credentials or decide when to send the header.
 *
 * <p>Credentials are read directly from the process environment — one shared
 * FID service account via {@code FID_USERNAME} / {@code FID_PASSWORD}. No
 * Spring binding, no yml plumbing: whoever runs the process sets the env vars
 * and the clients pick them up. If either is blank, the Authorization header
 * is skipped entirely (local-dev mocks and integration tests run this way).</p>
 */
public final class BasicAuthSupport {

    /** Env var names — single source of truth. */
    public static final String FID_USERNAME_ENV = "FID_USERNAME";
    public static final String FID_PASSWORD_ENV = "FID_PASSWORD";

    private BasicAuthSupport() {}

    /**
     * Reads {@code FID_USERNAME} / {@code FID_PASSWORD} from the environment and
     * returns {@code "Basic <base64(user:pass)>"}, or {@code null} if either env
     * var is unset or blank.
     */
    public static String fidHeader() {
        return header(System.getenv(FID_USERNAME_ENV), System.getenv(FID_PASSWORD_ENV));
    }

    /** Reads the {@code FID_USERNAME} env var, returning {@code null} if blank. */
    public static String fidUsername() {
        String u = System.getenv(FID_USERNAME_ENV);
        return isBlank(u) ? null : u;
    }

    /**
     * Returns the {@code "Basic <base64(user:pass)>"} header value, or
     * {@code null} if either input is blank.
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
