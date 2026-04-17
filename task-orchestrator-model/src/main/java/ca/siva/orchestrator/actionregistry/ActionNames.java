package ca.siva.orchestrator.actionregistry;

/**
 * Tiny container for the two shared literals the orchestrator needs at
 * compile time — the per-action name constants that used to live here have
 * been removed. The authoritative list of required {@code actionName} values
 * now lives in {@code application-orchestration.yml} under
 * {@code orchestrator.actionregistry.required-action-names} and is consumed
 * at runtime via {@code ActionRegistryProperties#requiredActionNames()}.
 *
 * <p>Keep this class dependency-free so the {@code task-orchestrator-model}
 * module (which has no Spring dependency) can own it.</p>
 */
public final class ActionNames {

    private ActionNames() {}

    // ------------------------------------------------------------------
    //  Shared constants still in use by ActionRegistry / TaskCommandFactory
    // ------------------------------------------------------------------
    public static final String DEFAULT     = "DEFAULT";
    public static final String DCX_KEY_DEL = ",";

    // ------------------------------------------------------------------
    //  Numeric code constants (action-code / dcx-action-code strings)
    //  Use these for direct string comparison, not actionName lookup.
    // ------------------------------------------------------------------
    public static final String ACTION_CODE_UNEXPECTED_FAILURE_KICKOUT    = "0000";
    public static final String ACTION_CODE_VR_SUCCESS                    = "7000";
    public static final String ACTION_CODE_VR_FAILURE_GENERIC_KICKOUT    = "7001";
    public static final String DCX_ACTION_CODE_VR_FAILURE_GENERIC_KICKOUT = "7101";
}
