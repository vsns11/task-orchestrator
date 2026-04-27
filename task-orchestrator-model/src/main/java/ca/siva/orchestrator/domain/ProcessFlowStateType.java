package ca.siva.orchestrator.domain;

import java.util.Optional;

/**
 * TMF-701 {@code processFlow.state} wire values.
 *
 * <p>The orchestrator only writes the two terminal states ({@code completed}
 * / {@code failed}) on the final-state PATCH; the transient values are
 * declared so a {@code fromValue} parse of a server-side state never
 * silently returns empty for a known TMF-701 vocabulary entry.</p>
 *
 * <p>Use {@link #getValue()} when serializing to the wire (or when calling
 * {@code Tmf701Client.patchProcessFlowFinalState}); use
 * {@link #fromValue(String)} when reading the {@code state} field off a
 * fetched processFlow.</p>
 */
public enum ProcessFlowStateType {

    /** Initial state — flow created but no actions started. */
    INITIAL("initial"),
    /** Flow is mid-execution. */
    IN_PROGRESS("inProgress"),
    /** Flow finished successfully — every batch closed cleanly. */
    COMPLETED("completed"),
    /** Flow finished unsuccessfully — kickout or non-retryable failure. */
    FAILED("failed"),
    /** Flow cancelled by an operator / upstream signal. */
    CANCELLED("cancelled");

    private final String value;

    ProcessFlowStateType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** Parses a wire-format state string to its enum constant. */
    public static Optional<ProcessFlowStateType> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ProcessFlowStateType s : values()) {
            if (s.value.equalsIgnoreCase(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
