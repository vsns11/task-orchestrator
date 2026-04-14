package ca.siva.orchestrator.actionregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Identity triplet for an action, loaded from the action-code registry API.
 *
 * <p>The registry only provides identity information — who the action is.
 * Execution behavior (SYNC/ASYNC, timeout, retries) is defined in the
 * DAG YAML, not here.</p>
 *
 * <ul>
 *   <li>{@code actionCode} — unique identifier (e.g. "VOICE_SERVICE_DIAGNOSTIC")</li>
 *   <li>{@code actionName} — human-readable method name (e.g. "runVoiceDiagnostic")</li>
 *   <li>{@code dcxActionCode} — external DCX system code (e.g. "DCX-VSD-01")</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionDefinition(
        String actionCode,
        String actionName,
        String dcxActionCode
) {}
