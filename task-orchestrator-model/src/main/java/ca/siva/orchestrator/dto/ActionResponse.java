package ca.siva.orchestrator.dto;

import ca.siva.orchestrator.dto.tmf.TaskFlow;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * The result payload attached to a COMPLETED {@code task.event}.
 *
 * <p>Carries the full downstream action response alongside taskFlow metadata.
 * Stored as JSON in the {@code task_execution.result_json} column and forwarded
 * as {@code dependencyResults} to downstream batches that declare {@code dependsOn}.</p>
 *
 * <p>Field conventions:</p>
 * <ul>
 *   <li>{@link #taskResult} — free-form {@code Object}: the raw response body
 *       the task-runner received from the downstream system (sync or async),
 *       deserialized into whatever shape makes sense ({@code JsonNode},
 *       {@code Map}, or a domain DTO). Kept loosely typed so any downstream
 *       payload can flow through without forcing a schema change.</li>
 *   <li>{@link #taskFlowResponse} — strongly typed TMF-701
 *       {@link TaskFlow} resource with state + characteristics carrying the
 *       domain outputs (status, diagnostic details, etc.).</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String code;
    private String id;
    private String type;

    /**
     * Raw downstream response body for this task — whatever the task-runner got
     * back from the sync or async downstream call, deserialized into a generic
     * object. Typically a {@code JsonNode} or {@code Map<String,Object>}, but
     * may be any serializable type.
     */
    private Object taskResult;

    /**
     * TMF-701 {@link TaskFlow} resource carrying the task-runner's business result.
     * Domain-specific outputs (status, diagnosticSummary, etc.) are surfaced as
     * {@code characteristic} entries per TMF convention, not as ad-hoc fields.
     */
    private TaskFlow taskFlowResponse;

    private Map<String, Object> additionalProps;
    private String workOrderCode;
    private String taskStatusCode;
    private String statusChangeReason;
}
