package ca.siva.orchestrator.dto;

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
    private String dummyResult;
    private Object taskFlowResult;
    private Object taskFlowResponse;
    private Map<String, Object> additionalProps;
    private String workOrderCode;
    private String taskStatusCode;
    private String statusChangeReason;
}
