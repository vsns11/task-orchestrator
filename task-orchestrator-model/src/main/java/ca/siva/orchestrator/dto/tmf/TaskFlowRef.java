package ca.siva.orchestrator.dto.tmf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * TMF-701 {@code TaskFlowRef} — a lightweight reference to a TaskFlow resource.
 *
 * <p>Matches the TMF-701 v4.0.0 swagger {@code TaskFlowRef} definition:</p>
 * <pre>
 * {
 *   "id":            "string",   // required
 *   "href":          "string",
 *   "@referredType": "string"
 * }
 * </pre>
 *
 * <p>Used wherever a lightweight pointer to a TaskFlow is needed — e.g. inside
 * {@link TaskFlow.TaskFlowRelationship} to link one TaskFlow to another.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskFlowRef implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** TMF-701 taskFlow UUID (required). */
    private String id;

    /** Canonical URL of the taskFlow resource in TMF-701. */
    private String href;

    @JsonProperty("@referredType")
    private String referredType;
}
