package ca.siva.orchestrator.dto;

import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.Intent;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.domain.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform DTO for all messages on the {@code task.command} and
 * {@code notification.management} Kafka topics.
 *
 * <p>Dispatch logic uses {@code messageName} + {@code source} to route handling.
 * Fields not relevant to a given messageName are omitted (null) and excluded
 * from JSON via {@link JsonInclude.Include#NON_NULL}.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCommand {

    // ---- metadata ----
    private String      eventId;
    private String      correlationId;
    private String      schemaVersion = "1.0";
    private Instant     eventTime;
    private MessageType messageType;
    private String      messageName;
    private String      source;

    // ---- domain fields ----
    private String     dagKey;
    private Intent     intent;
    private TaskStatus status;

    // ---- nested structures ----
    private Batch          batch;
    private Action         action;
    private Task           task;
    private Execution      execution;
    private Downstream     downstream;
    private AwaitingSignal awaitingSignal;
    private Inputs         inputs;
    private ActionResponse     result;
    private ErrorInfo      error;

    /** Batch position within the DAG execution. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Batch {
        private Integer index;
        private Integer total;
    }

    /** Action triplet identifying the work to execute. actionName is the primary key. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        private String actionName;
        private String actionCode;
        private String dcxActionCode;
    }

    /** TaskFlow reference minted by the runner. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        private String id;
        private String href;
    }

    /** Execution parameters and timing metadata. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Execution {
        private ExecutionMode mode;
        private Long          timeoutMs;
        private Instant       startedAt;
        private Instant       finishedAt;
        private Long          durationMs;
    }

    /** Downstream system reference (e.g. external diagnostic call). */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Downstream {
        private String id;
        private String href;
    }

    /** Correlation data for async tasks awaiting an external signal. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwaitingSignal {
        private String downstreamTransactionId;
    }

    /**
     * Input payloads carried by task commands and signals.
     *
     * <ul>
     *   <li>{@code processFlow} — the typed TMF-701 processFlow object (for task.execute)</li>
     *   <li>{@code downstream} — downstream reference (for task.signal)</li>
     *   <li>{@code dependencyResults} — results from previous actions this task depends on,
     *       keyed by actionName. Only present when the DAG action declares {@code dependsOn}.</li>
     *   <li>{@code externalEventId}, {@code externalType}, {@code reportingSystem} — external
     *       trigger metadata carried by {@code task.signal} messages.</li>
     * </ul>
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Inputs {
        private ProcessFlow         processFlow;
        private Downstream          downstream;
        private Map<String, Object> dependencyResults;
        private String              externalEventId;
        private String              externalType;
        private String              reportingSystem;
    }

    /** Error details attached to FAILED task events. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorInfo {
        private String  code;
        private String  message;
        private Boolean retryable;
        private Map<String, Object> details;
    }
}
