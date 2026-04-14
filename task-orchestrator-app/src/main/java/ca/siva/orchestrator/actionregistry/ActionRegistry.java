package ca.siva.orchestrator.actionregistry;

import ca.siva.orchestrator.config.ActionRegistryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads action identity data from two separate APIs and provides chained lookup.
 *
 * <p>Two maps, two APIs, chained lookup — mirrors the real PAM ActionCodeService:</p>
 * <ol>
 *   <li>{@code GET /action-codes} → builds {@code actionName → ActionCodeEntry} map</li>
 *   <li>{@code GET /dcx-action-codes} → builds {@code parent (=actionCode) → DcxActionCodeEntry} map</li>
 * </ol>
 *
 * <p>Lookup chain: actionName → actionCode → dcxActionCode</p>
 * <ol>
 *   <li>{@code findByName("runVoiceDiagnostic")} → ActionCodeEntry with actionCode=VOICE_SERVICE_DIAGNOSTIC</li>
 *   <li>{@code findDcxByActionCode("VOICE_SERVICE_DIAGNOSTIC")} → DcxActionCodeEntry with dcxActionCode=DCX-VSD-01</li>
 * </ol>
 *
 * <p>For convenience, {@code resolve(actionName)} does both lookups and returns a merged
 * {@link ActionDefinition} with all three fields.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionRegistry {

    private final ActionRegistryProperties props;
    private final RestClient.Builder       builder;
    private final Environment              environment;

    /** actionName → ActionCodeEntry (from GET /action-codes) */
    private final Map<String, ActionCodeEntry> actionCodesByName = new ConcurrentHashMap<>();

    /** actionCode (= parent) → DcxActionCodeEntry (from GET /dcx-action-codes) */
    private final Map<String, DcxActionCodeEntry> dcxCodesByActionCode = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        reload();
    }

    @Scheduled(cron = "${orchestrator.actionregistry.reload-cron:0 0 2 * * *}")
    public void scheduledReload() {
        log.info("Scheduled action registry reload triggered");
        reload();
    }

    /**
     * Loads both APIs and populates the two maps.
     */
    public void reload() {
        String baseUrl = resolveBaseUrl();
        RestClient client = builder.baseUrl(baseUrl).build();

        try {
            loadActionCodes(client);
            loadDcxActionCodes(client);

            log.info("Action registry loaded: {} action codes, {} dcx codes from {}",
                    actionCodesByName.size(), dcxCodesByActionCode.size(), baseUrl);
        } catch (Exception e) {
            log.error("Failed to load action registry from {}: {}", baseUrl, e.getMessage());
        }
    }

    /**
     * Looks up ActionCodeEntry by actionName.
     * First step in the chain: actionName → actionCode.
     */
    public Optional<ActionCodeEntry> findByName(String actionName) {
        return Optional.ofNullable(actionCodesByName.get(actionName));
    }

    /**
     * Looks up DcxActionCodeEntry by actionCode.
     * Second step in the chain: actionCode (= parent) → dcxActionCode.
     */
    public Optional<DcxActionCodeEntry> findDcxByActionCode(String actionCode) {
        return Optional.ofNullable(dcxCodesByActionCode.get(actionCode));
    }

    /**
     * Convenience method: resolves actionName → full ActionDefinition in one call.
     *
     * <p>Chains both lookups:</p>
     * <ol>
     *   <li>actionName → ActionCodeEntry → get actionCode</li>
     *   <li>actionCode → DcxActionCodeEntry → get dcxActionCode</li>
     * </ol>
     *
     * @return merged ActionDefinition, or empty if actionName not found
     */
    public Optional<ActionDefinition> resolve(String actionName) {
        return findByName(actionName).map(actionEntry -> {
            String dcxActionCode = findDcxByActionCode(actionEntry.getActionCode())
                    .map(DcxActionCodeEntry::getDcxActionCode)
                    .orElse("UNKNOWN");

            return new ActionDefinition(
                    actionEntry.getActionCode(),
                    actionEntry.getActionName(),
                    dcxActionCode);
        });
    }

    // ---- private loading methods ----

    private void loadActionCodes(RestClient client) {
        List<ActionCodeEntry> entries = client.get()
                .uri(props.actionCodesPath())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (entries == null) entries = List.of();

        actionCodesByName.clear();
        entries.forEach(entry -> actionCodesByName.put(entry.getActionName(), entry));

        log.info("  Loaded {} action codes (actionName → actionCode)", actionCodesByName.size());
        actionCodesByName.forEach((name, entry) ->
                log.debug("    {} → {}", name, entry.getActionCode()));
    }

    private void loadDcxActionCodes(RestClient client) {
        List<DcxActionCodeEntry> entries = client.get()
                .uri(props.dcxActionCodesPath())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (entries == null) entries = List.of();

        dcxCodesByActionCode.clear();
        entries.forEach(entry -> dcxCodesByActionCode.put(entry.getParent(), entry));

        log.info("  Loaded {} dcx action codes (actionCode → dcxActionCode)", dcxCodesByActionCode.size());
        dcxCodesByActionCode.forEach((parent, entry) ->
                log.debug("    {} → {}", parent, entry.getDcxActionCode()));
    }

    private String resolveBaseUrl() {
        return Optional.ofNullable(environment.getProperty("local.server.port"))
                .map(port -> props.baseUrl().replaceFirst(":\\d+/", ":" + port + "/"))
                .orElse(props.baseUrl());
    }
}
