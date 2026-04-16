package ca.siva.orchestrator.domain;

import java.util.Optional;

/**
 * Kafka {@code messageName} values used in the uniform envelope.
 * These drive the orchestrator's dispatch logic.
 *
 * <p>The enum carries the wire-format string value (e.g. {@code "processFlow.initiated"})
 * which is what gets serialized to JSON. Use {@link #getValue()} when writing to
 * the envelope, and {@link #fromValue(String)} when reading from an incoming message.</p>
 */
public enum MessageName {

    PROCESS_FLOW_INITIATED("processFlow.initiated"),
    TASK_EXECUTE("task.execute"),
    TASK_EVENT("task.event"),
    TASK_SIGNAL("task.signal"),
    FLOW_LIFECYCLE("flow.lifecycle");

    private final String value;

    MessageName(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** Parses a wire-format messageName string to its enum constant. */
    public static Optional<MessageName> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (MessageName m : values()) {
            if (m.value.equals(value)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
