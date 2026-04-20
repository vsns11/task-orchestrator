package ca.siva.orchestrator.actionregistry;

import ca.siva.orchestrator.client.BasicAuthSupport;
import ca.siva.orchestrator.config.ActionRegistryProperties;
import ca.siva.orchestrator.config.FidCredentialsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static ca.siva.orchestrator.actionregistry.ActionNames.DCX_KEY_DEL;
import static ca.siva.orchestrator.actionregistry.ActionNames.DEFAULT;

/**
 * In-process replica of the upstream {@code ca.bell.itsa.sharp.bpm2.ActionBuilder}
 * — same public behaviour, same lookup chains, same key building.
 *
 * <h3>Two maps, two APIs:</h3>
 * <ol>
 *   <li>{@code GET /action-codes} → {@code actionName → ActionCodeEntry}
 *       (private field {@link #mapActionNameToActionCode}).</li>
 *   <li>{@code GET /dcx-action-codes} → {@code (actionCode,flowType,modemType) → DcxActionCodeEntry}
 *       (private field {@link #mapActionCodeToDcxActionCode}).</li>
 * </ol>
 *
 * <p>The DCX map is keyed with a 3-tuple composite built by
 * {@link #buildKeyForDcxActionCode(String, String, String)} — this matches
 * ActionBuilder.buildKeyForDcxActionCode and lets us disambiguate DCX rows that
 * share a parent but differ in flowType/modemType. When the caller doesn't
 * care, the DEFAULT/DEFAULT entry is used (most rows from the registry).</p>
 *
 * <h3>Primary lookup methods (mirror ActionBuilder):</h3>
 * <ul>
 *   <li>{@link #getActionCodeByName(String)} — actionName → actionCode</li>
 *   <li>{@link #getDcxActionCodeByName(String, String, String)} — actionName + flowType + modemType → dcxActionCode, with DEFAULT/DEFAULT fallback</li>
 *   <li>{@link #getActionNameByActionCode(String)} — actionCode → actionName (reverse)</li>
 *   <li>{@link #getActionCodeOrDefault(String, String)} / {@link #getActionNameOrDefault(String, String)} — default-fallback variants</li>
 *   <li>{@link #getKickOutActionCodeOrDefault(String, String)} / {@link #getKickOutDcxActionCodeOrDefault(String, String, String, String)} — kickout variants</li>
 *   <li>{@link #containsActionCode(String)} / {@link #containsDcxActionCodeKey(String, String, String)} — null-safe membership checks</li>
 *   <li>{@link #codeStartsWith0(String)} — ActionBuilder branching rule:
 *       codes starting with {@code '0'} swap the bpmnType slot for DEFAULT and use
 *       the real modemType, everything else uses the real bpmnType and DEFAULT modem.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionRegistry {

    // --- log-message constants (extracted to satisfy Sonar S1192) ----------
    private static final String API_ACTION_CODES     = "action-codes";
    private static final String API_DCX_ACTION_CODES = "dcx-action-codes";
    private static final String STATE_REFRESHED      = "refreshed";
    private static final String STATE_KEPT           = "kept";
    private static final String MSG_KEEP_EXISTING    = "{} API returned empty — keeping existing {} entries";
    private static final String MSG_ALL_INVALID      = "{} API returned {} row(s) but all were invalid — keeping existing {} entries";
    private static final String MSG_FETCH_FAILED     = "{} fetch failed from {} — keeping existing {} entries, exception={}";
    private static final String UNKNOWN_DCX          = "UNKNOWN";

    private final ActionRegistryProperties props;
    private final FidCredentialsProperties fidCreds;
    private final RestClient.Builder       builder;
    private final Environment              environment;

    /** actionName → ActionCodeEntry (from GET /action-codes). Named after ActionBuilder's field. */
    private final Map<String, ActionCodeEntry> mapActionNameToActionCode = new ConcurrentHashMap<>();

    /** composite "actionCode,flowType,modemType" → DcxActionCodeEntry (from GET /dcx-action-codes). */
    private final Map<String, DcxActionCodeEntry> mapActionCodeToDcxActionCode = new ConcurrentHashMap<>();

    /** Becomes true only after a successful reload. Used by the health indicator. */
    private volatile boolean ready;

    /**
     * Flipped to false after the very first successful reload. The initial load
     * performs a strict fail-fast validation (every {@code ACTION_*} constant in
     * {@link ActionNames} must be present in the registry); scheduled reloads
     * skip that check so a transient upstream outage or a lagging registry
     * rollout does not bring a healthy pod down.
     */
    private final AtomicBoolean firstLoad = new AtomicBoolean(true);

    /** Returns true if at least one successful reload has populated both maps. */
    public boolean isReady() {
        return ready && !mapActionNameToActionCode.isEmpty() && !mapActionCodeToDcxActionCode.isEmpty();
    }

    public int actionCount() {
        return mapActionNameToActionCode.size();
    }

    public int dcxActionCount() {
        return mapActionCodeToDcxActionCode.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        log.info("Initial action registry load triggered");
        reload();
        // Fail-fast ONLY on the initial boot: if a required actionName is
        // missing, the orchestrator can't safely publish task.execute
        // commands, so we abort the JVM rather than serve traffic with a
        // half-wired registry. Scheduled reloads never exit — see reload().
        validateActionNamesOrExit();
    }

    @Scheduled(cron = "${orchestrator.actionregistry.reload-cron:0 0 2 * * *}")
    public void scheduledReload() {
        log.info("Scheduled action registry reload triggered");
        reload();
    }

    /**
     * Loads both APIs and populates the two maps.
     *
     * <p>Credentials come from {@link FidCredentialsProperties} (bound to
     * {@code orchestrator.fid.*} in yml, which references the
     * {@code FID_USERNAME} / {@code FID_PASSWORD} env vars). The RestClient is
     * rebuilt on each reload so a fresh value is used on every scheduled
     * refresh. Blank values disable the Authorization header (used for
     * local-dev mocks and integration tests).</p>
     */
    public void reload() {
        String baseUrl = resolveBaseUrl();
        String authHeader = BasicAuthSupport.header(fidCreds.username(), fidCreds.password());
        RestClient client = buildClient(baseUrl, authHeader);

        // Each loader is a no-op on failure or empty response — existing map
        // contents are preserved so a cron reload that hits a 5xx or an empty
        // payload never leaves the orchestrator with an empty registry.
        boolean actionCodesLoaded = tryLoadActionCodes(client, baseUrl);
        boolean dcxCodesLoaded    = tryLoadDcxActionCodes(client, baseUrl);

        // `ready` latches true after the first successful population. We don't
        // flip it back to false on a later failure — the maps still hold the
        // previous snapshot, which is exactly the point of not clearing.
        if (!mapActionNameToActionCode.isEmpty() && !mapActionCodeToDcxActionCode.isEmpty()) {
            ready = true;
        }

        log.info("Action registry reload finished (actionCodes={}, dcxCodes={}) — now {} action codes, {} dcx codes from {} (basicAuth={})",
                actionCodesLoaded ? STATE_REFRESHED : STATE_KEPT,
                dcxCodesLoaded    ? STATE_REFRESHED : STATE_KEPT,
                mapActionNameToActionCode.size(), mapActionCodeToDcxActionCode.size(), baseUrl,
                describeAuth(authHeader));
    }

    private RestClient buildClient(String baseUrl, String authHeader) {
        RestClient.Builder b = builder.baseUrl(baseUrl);
        if (authHeader != null) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return b.build();
    }

    private String describeAuth(String authHeader) {
        return authHeader != null ? "enabled (user=" + fidCreds.username() + ")" : "disabled";
    }

    private boolean tryLoadActionCodes(RestClient client, String baseUrl) {
        try {
            return loadActionCodes(client);
        } catch (RuntimeException e) {
            // Catches RestClientException (transport/HTTP errors) AND
            // HttpMessageConversionException (Jackson deserialization errors when
            // the upstream returns a body that doesn't deserialize to the
            // expected List<ActionCodeEntry>). Either way the in-memory registry
            // is preserved per the "keep existing" contract.
            log.error(MSG_FETCH_FAILED,
                    API_ACTION_CODES, baseUrl, mapActionNameToActionCode.size(), e.toString(), e);
            return false;
        }
    }

    private boolean tryLoadDcxActionCodes(RestClient client, String baseUrl) {
        try {
            return loadDcxActionCodes(client);
        } catch (RuntimeException e) {
            log.error(MSG_FETCH_FAILED,
                    API_DCX_ACTION_CODES, baseUrl, mapActionCodeToDcxActionCode.size(), e.toString(), e);
            return false;
        }
    }

    /**
     * Fail-fast check invoked once after the initial load. Every
     * {@code ACTION_*} constant declared on {@link ActionNames} (except the
     * numeric {@code ACTION_CODE_*} ones) must resolve to an actionCode in the
     * registry — otherwise we exit the JVM so Kubernetes/pod manager restarts
     * us rather than serving traffic with an incomplete registry.
     *
     * <p>On scheduled reloads this is never called, because a transient
     * upstream error shouldn't kill a healthy pod.</p>
     */
    // Sonar S1147 flags System.exit — suppressed because fail-fast at startup
    // is the intended, documented behaviour: the pod manager (Kubernetes)
    // restarts us rather than serving traffic with an incomplete registry.
    @SuppressWarnings("java:S1147")
    void validateActionNamesOrExit() {
        if (!firstLoad.compareAndSet(true, false)) {
            return;
        }
        List<String> expected = props.requiredActionNames();
        if (expected.isEmpty()) {
            log.warn("orchestrator.actionregistry.required-action-names is empty — skipping"
                    + " startup allowlist validation. Populate the YAML list to enable fail-fast.");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String name : expected) {
            if (!containsActionCode(name)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            log.info("All {} required actionNames validated against the registry", expected.size());
            return;
        }
        log.error("Action registry is missing {} required actionName(s) declared in"
                + " orchestrator.actionregistry.required-action-names:", missing.size());
        missing.forEach(n -> log.error("  - {}", n));
        log.error("Fail-fast at startup: the orchestrator cannot publish task.execute commands"
                + " without all action codes present. Exiting JVM.");
        System.exit(1);
    }

    // =================================================================
    //  Membership checks (ActionBuilder.containsActionCode / containsDcxActionCodeKey)
    // =================================================================

    /** True when {@code actionName} is in the action-code map. Null-safe. */
    public boolean containsActionCode(String actionName) {
        return actionName != null && mapActionNameToActionCode.get(actionName) != null;
    }

    /**
     * True when the composite DCX key {@code (actionCode,flowType,modemType)}
     * derived from the given {@code actionName} is in the DCX map. Matches
     * {@code ActionBuilder.containsDcxActionCodeKey}.
     */
    public boolean containsDcxActionCodeKey(String actionName, String flowType, String modemType) {
        if (!containsActionCode(actionName)) {
            return false;
        }
        String actionCode = getActionCodeByName(actionName);
        String key = buildKeyForDcxActionCode(actionCode, flowType, modemType);
        return mapActionCodeToDcxActionCode.containsKey(key);
    }

    // =================================================================
    //  Name → code (ActionBuilder.getActionCode / getActionType)
    // =================================================================

    /**
     * {@code actionName → actionCode}. Returns {@code null} if the name isn't
     * registered. Mirrors {@code ActionBuilder.getActionCode(actionName, kcontext)}.
     */
    public String getActionCodeByName(String actionName) {
        if (actionName == null) {
            return null;
        }
        ActionCodeEntry entry = mapActionNameToActionCode.get(actionName);
        return entry != null ? entry.getActionCode() : null;
    }

    /**
     * {@code actionName → actionType} (e.g. {@code MS}, {@code CDA}, {@code Kickout}).
     * Mirrors {@code ActionBuilder.getActionType(actionName, kcontext)}.
     */
    public String getActionType(String actionName) {
        if (actionName == null) {
            return null;
        }
        ActionCodeEntry entry = mapActionNameToActionCode.get(actionName);
        return entry != null ? entry.getActionType() : null;
    }

    /**
     * {@code actionCode → actionName} reverse lookup — iterates the action-code
     * map once and returns the first entry whose {@code actionCode} matches.
     * Mirrors {@code ActionBuilder.getActionNameByActionCode}.
     */
    public String getActionNameByActionCode(String actionCode) {
        if (actionCode == null) {
            return null;
        }
        for (Map.Entry<String, ActionCodeEntry> e : mapActionNameToActionCode.entrySet()) {
            ActionCodeEntry entry = e.getValue();
            if (entry != null && actionCode.equals(entry.getActionCode())) {
                return e.getKey();
            }
        }
        return null;
    }

    // =================================================================
    //  Default-fallback variants
    //  (ActionBuilder.getActionCodeOrDefault / getActionNameOrDefault)
    // =================================================================

    /**
     * Returns the actionCode for {@code actionName}, or — when not registered —
     * the actionCode for {@code defaultActionName}. Mirrors
     * {@code ActionBuilder.getActionCodeOrDefault}.
     */
    public String getActionCodeOrDefault(String actionName, String defaultActionName) {
        if (containsActionCode(actionName)) {
            return getActionCodeByName(actionName);
        }
        return getActionCodeByName(defaultActionName);
    }

    /**
     * Returns {@code actionName} when it's registered, otherwise
     * {@code defaultActionName}. Mirrors
     * {@code ActionBuilder.getActionNameOrDefault}.
     */
    public String getActionNameOrDefault(String actionName, String defaultActionName) {
        Objects.requireNonNull(defaultActionName);
        return containsActionCode(actionName) ? actionName : defaultActionName;
    }

    // =================================================================
    //  Kickout variants (ActionBuilder.getKickOutActionCodeOrDefault /
    //                     getKickOutDcxActionCodeOrDefault)
    // =================================================================

    /**
     * Kickout-friendly variant of {@link #getActionCodeOrDefault(String, String)}.
     * Preserved separately to mirror ActionBuilder's public API so call sites
     * map one-for-one.
     */
    public String getKickOutActionCodeOrDefault(String actionName, String defaultActionName) {
        return getActionCodeOrDefault(actionName, defaultActionName);
    }

    /**
     * Looks up the DCX action code for {@code actionName} (kickout variant).
     * <p>Returns {@code defaultDcx} when the full composite-key lookup (with
     * DEFAULT/DEFAULT fallback) misses. Mirrors
     * {@code ActionBuilder.getKickOutDcxActionCodeOrDefault}.</p>
     */
    public String getKickOutDcxActionCodeOrDefault(String actionName, String flowType,
                                                    String modemType, String defaultDcx) {
        String dcx = getDcxActionCodeByName(actionName, flowType, modemType);
        return dcx != null ? dcx : defaultDcx;
    }

    // =================================================================
    //  actionName → dcxActionCode (ActionBuilder.getDcxCodeByActionName)
    // =================================================================

    /**
     * {@code actionName → dcxActionCode} using the ActionBuilder lookup rules:
     * <ol>
     *   <li>Resolve {@code actionCode} via {@link #getActionCodeByName(String)}.
     *       Return {@code null} if the name isn't registered.</li>
     *   <li>Pick the DCX key according to {@link #codeStartsWith0(String)}:
     *     <ul>
     *       <li>code starts with {@code '0'} → key is
     *           {@code (actionCode, DEFAULT, modemType)}</li>
     *       <li>otherwise → key is {@code (actionCode, flowType, DEFAULT)}</li>
     *     </ul>
     *   </li>
     *   <li>On miss, fall back to {@code (actionCode, DEFAULT, DEFAULT)}.</li>
     * </ol>
     * Returns {@code null} only when every lookup misses.
     */
    public String getDcxActionCodeByName(String actionName, String flowType, String modemType) {
        String actionCode = getActionCodeByName(actionName);
        if (actionCode == null) {
            return null;
        }

        String key;
        if (codeStartsWith0(actionCode)) {
            key = buildKeyForDcxActionCode(actionCode, DEFAULT, modemType);
        } else {
            key = buildKeyForDcxActionCode(actionCode, flowType, DEFAULT);
        }

        DcxActionCodeEntry dcxEntry = mapActionCodeToDcxActionCode.get(key);
        String dcxActionCode = dcxEntry != null ? dcxEntry.getDcxActionCode() : null;
        if (dcxActionCode != null) {
            return dcxActionCode;
        }

        // Fallback to the DEFAULT/DEFAULT row — matches ActionBuilder.getDcxCodeByActionName
        String fallbackKey = buildKeyForDcxActionCode(actionCode, DEFAULT, DEFAULT);
        DcxActionCodeEntry fallbackEntry = mapActionCodeToDcxActionCode.get(fallbackKey);
        return fallbackEntry != null ? fallbackEntry.getDcxActionCode() : null;
    }

    /**
     * Convenience overload: defaults both dimensions to DEFAULT. Useful at call
     * sites (DAG-driven flow) that don't track flowType / modemType.
     */
    public String getDcxActionCodeByName(String actionName) {
        return getDcxActionCodeByName(actionName, DEFAULT, DEFAULT);
    }

    // =================================================================
    //  Key building (ActionBuilder.buildKeyForDcxActionCode / codeStartsWith0)
    // =================================================================

    /**
     * Builds the composite DCX map key {@code actionCode,flowType,modemType}
     * using the same delimiter as upstream ({@link ActionNames#DCX_KEY_DEL}).
     * Mirrors {@code ActionBuilder.buildKeyForDcxActionCode}.
     */
    public static String buildKeyForDcxActionCode(String actionCode, String flowType, String modemType) {
        return new StringJoiner(DCX_KEY_DEL)
                .add(nullToDefault(actionCode))
                .add(nullToDefault(flowType))
                .add(nullToDefault(modemType))
                .toString();
    }

    /**
     * Kickout-branch discriminator from ActionBuilder. Returns {@code true}
     * when the action code is non-null, non-empty and its first character is
     * {@code '0'}. The extra empty check avoids {@link StringIndexOutOfBoundsException}
     * on zero-length codes that could otherwise slip through the registry.
     */
    public static boolean codeStartsWith0(String actionCode) {
        return actionCode != null && !actionCode.isEmpty() && actionCode.charAt(0) == '0';
    }

    // =================================================================
    //  Convenience: resolve actionName → ActionDefinition (actionCode + dcxActionCode)
    // =================================================================

    /**
     * One-shot resolver used at task.execute build time: returns a merged
     * {@link ActionDefinition} with the actionCode and the DEFAULT/DEFAULT
     * dcxActionCode. The DAG doesn't track flowType/modemType, so this
     * convenience layer pins both to DEFAULT — call
     * {@link #getDcxActionCodeByName(String, String, String)} directly when
     * you have the real discriminators.
     *
     * @return merged ActionDefinition, or empty if actionName not registered
     */
    public Optional<ActionDefinition> resolve(String actionName) {
        if (!containsActionCode(actionName)) {
            return Optional.empty();
        }
        String actionCode = getActionCodeByName(actionName);
        String dcxActionCode = getDcxActionCodeByName(actionName, DEFAULT, DEFAULT);
        return Optional.of(new ActionDefinition(
                actionCode, actionName, dcxActionCode != null ? dcxActionCode : UNKNOWN_DCX));
    }

    // =================================================================
    //  Optional-wrapped accessors for callers that prefer Optional
    // =================================================================

    /** Null-safe Optional wrapper around the action-code map. */
    public Optional<ActionCodeEntry> findByName(String actionName) {
        if (actionName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapActionNameToActionCode.get(actionName));
    }

    /**
     * Null-safe Optional wrapper around the DCX map, using the composite
     * {@code (actionCode,flowType,modemType)} key. Use
     * {@link #buildKeyForDcxActionCode(String, String, String)} to build the
     * key when you need a different dimension combination.
     */
    public Optional<DcxActionCodeEntry> findDcxByActionCode(String actionCode,
                                                             String flowType, String modemType) {
        if (actionCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                mapActionCodeToDcxActionCode.get(buildKeyForDcxActionCode(actionCode, flowType, modemType)));
    }

    // =================================================================
    //  Private loading / helpers
    // =================================================================

    /**
     * Fetches the action-codes payload and atomically swaps the in-memory
     * map — but only when the API returned usable data. A null/empty response
     * or one where every row is invalid is treated as "registry unchanged" so
     * a flaky upstream call during the nightly cron can't empty the map.
     *
     * @return {@code true} when the map was refreshed from a non-empty
     *         response, {@code false} when existing entries were preserved
     */
    private boolean loadActionCodes(RestClient client) {
        List<ActionCodeEntry> entries = client.get()
                .uri(props.actionCodesPath())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (entries == null || entries.isEmpty()) {
            log.warn(MSG_KEEP_EXISTING, API_ACTION_CODES, mapActionNameToActionCode.size());
            return false;
        }

        // Build into a staging map so a partial/failed parse never mutates
        // the live map. ConcurrentHashMap rejects null keys/values with NPE,
        // so filter defensively rather than letting one bad row crash us.
        Map<String, ActionCodeEntry> fresh = new HashMap<>();
        int skipped = 0;
        for (ActionCodeEntry entry : entries) {
            if (entry == null || entry.getActionName() == null || entry.getActionName().isBlank()) {
                skipped++;
                log.warn("Skipping {} entry with null/blank actionName: {}", API_ACTION_CODES, entry);
                continue;
            }
            fresh.put(entry.getActionName(), entry);
        }

        if (fresh.isEmpty()) {
            log.warn(MSG_ALL_INVALID, API_ACTION_CODES, entries.size(), mapActionNameToActionCode.size());
            return false;
        }

        // Two-phase swap that never exposes an empty map to concurrent readers:
        //   1. putAll(fresh)  — live map now holds (old ∪ new); every actionName
        //      that exists in either snapshot is resolvable during this window.
        //   2. retainAll(fresh.keySet()) — drop keys present in the old snapshot
        //      but absent from the new payload (server-side deletions).
        // clear()+putAll() would briefly expose an empty map to
        // getActionCodeByName() callers; this sequencing eliminates that gap.
        mapActionNameToActionCode.putAll(fresh);
        mapActionNameToActionCode.keySet().retainAll(fresh.keySet());

        log.info("  Loaded {} action codes (actionName → actionCode){}",
                mapActionNameToActionCode.size(), skippedSuffix(skipped, "actionName"));
        mapActionNameToActionCode.forEach((name, entry) ->
                log.debug("    {} → {}", name, entry.getActionCode()));
        return true;
    }

    /**
     * Fetches the dcx-action-codes payload and atomically swaps the in-memory
     * map — but only when the API returned usable data. Same keep-on-empty
     * contract as {@link #loadActionCodes(RestClient)}.
     *
     * @return {@code true} when the map was refreshed from a non-empty
     *         response, {@code false} when existing entries were preserved
     */
    private boolean loadDcxActionCodes(RestClient client) {
        List<DcxActionCodeEntry> entries = client.get()
                .uri(props.dcxActionCodesPath())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (entries == null || entries.isEmpty()) {
            log.warn(MSG_KEEP_EXISTING, API_DCX_ACTION_CODES, mapActionCodeToDcxActionCode.size());
            return false;
        }

        Map<String, DcxActionCodeEntry> fresh = new HashMap<>();
        int skipped = 0;
        int duplicates = 0;
        for (DcxActionCodeEntry entry : entries) {
            if (entry == null || entry.getParent() == null || entry.getParent().isBlank()) {
                skipped++;
                log.warn("Skipping {} entry with null/blank parent: {}", API_DCX_ACTION_CODES, entry);
                continue;
            }
            // Key matches ActionBuilder.buildKeyForDcxActionCode:
            //   parent (= actionCode) + "," + flowType + "," + modemType
            // Null flowType / modemType are coerced to DEFAULT so the lookup
            // side (which always passes concrete values) stays consistent.
            String key = buildKeyForDcxActionCode(entry.getParent(), entry.getFlowType(), entry.getModemType());
            DcxActionCodeEntry previous = fresh.put(key, entry);
            if (previous != null) {
                duplicates++;
                log.warn("Duplicate DCX entry for key {} — keeping the latest ({} → {}), previous was {}",
                        key, entry.getParent(), entry.getDcxActionCode(), previous.getDcxActionCode());
            }
        }

        if (fresh.isEmpty()) {
            log.warn(MSG_ALL_INVALID, API_DCX_ACTION_CODES, entries.size(), mapActionCodeToDcxActionCode.size());
            return false;
        }

        // Same two-phase swap as loadActionCodes — keep concurrent readers safe
        // from an empty-window that a clear()+putAll() pair would expose.
        mapActionCodeToDcxActionCode.putAll(fresh);
        mapActionCodeToDcxActionCode.keySet().retainAll(fresh.keySet());

        log.info("  Loaded {} dcx action codes (composite key → dcxActionCode){}{}",
                mapActionCodeToDcxActionCode.size(),
                skippedSuffix(skipped, "parent"),
                duplicates > 0 ? " — " + duplicates + " duplicate key(s) overwritten" : "");
        mapActionCodeToDcxActionCode.forEach((key, entry) ->
                log.debug("    {} → {}", key, entry.getDcxActionCode()));
        return true;
    }

    private static String skippedSuffix(int skipped, String field) {
        return skipped > 0 ? " — " + skipped + " skipped (null/blank " + field + ")" : "";
    }

    private static String nullToDefault(String value) {
        return (value == null || value.isBlank()) ? DEFAULT : value;
    }

    private String resolveBaseUrl() {
        return Optional.ofNullable(environment.getProperty("local.server.port"))
                .map(port -> props.baseUrl().replaceFirst(":\\d+/", ":" + port + "/"))
                .orElse(props.baseUrl());
    }
}
