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
import java.util.List;
import java.util.Objects;

/**
 * TMF-701 {@code TaskFlow} resource.
 *
 * <p>Matches the TMF-701 v4.0.0 swagger {@code TaskFlow} schema. This is the
 * full TaskFlow representation returned by the downstream task runtime:
 * state + characteristics + related entities + relationships.</p>
 *
 * <p>Used as the value of {@code ActionResponse.taskFlowResponse} — it carries
 * the completed taskFlow's business result, with domain-specific outputs
 * (e.g. {@code outcome=PASS}, diagnostic summaries) surfaced as
 * {@link ProcessFlow.Characteristic} entries per the TMF convention.</p>
 *
 * <p>The {@code Characteristic} and {@code RelatedEntity} inner types are reused
 * from {@link ProcessFlow} to keep the model consistent.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskFlow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String href;

    @JsonProperty("@type")
    private String type;

    @JsonProperty("@baseType")
    private String baseType;

    /** TMF-701 lifecycle state (e.g. {@code completed}, {@code failed}). */
    private String state;

    /** Spec reference that produced this taskFlow. */
    private String taskFlowSpecification;

    private String completionMethod;
    private Boolean isMandatory;
    private Integer priority;

    private List<Object> channel;
    private List<Object> relatedParty;

    private List<ProcessFlow.RelatedEntity> relatedEntity;

    /**
     * Name/value pairs. Domain outputs (outcome, diagnosticSummary, latencyMs, …)
     * are represented as characteristics, per TMF convention.
     */
    private List<ProcessFlow.Characteristic> characteristic;

    private List<TaskFlowRelationship> taskFlowRelationship;

    /**
     * Convenience: returns the value of the first characteristic with the given
     * name (case-insensitive), or {@code null} if none exists. Does NOT throw
     * on missing/empty lists — safe to call on sparsely populated responses.
     */
    public String findCharacteristic(String name) {
        if (characteristic == null || name == null) {
            return null;
        }
        for (ProcessFlow.Characteristic c : characteristic) {
            if (c != null && name.equalsIgnoreCase(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** TMF-701 {@code TaskFlowRelationship} — link to another taskFlow. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskFlowRelationship implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String relationshipType;
        private TaskFlowRef taskFlow;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskFlow that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
