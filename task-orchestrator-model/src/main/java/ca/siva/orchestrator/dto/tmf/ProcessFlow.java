package ca.siva.orchestrator.dto.tmf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * TMF-701 processFlow payload.
 *
 * <p>Matches the real TMF-701 processFlowManagement v4 API structure.
 * The pamconsumer reads this from {@code notification.management}, extracts
 * {@code processFlowSpecification} to determine the DAG, and publishes a
 * {@code processFlow.initiated} TaskCommand to the {@code task.command} topic.</p>
 *
 * <p>The orchestrator never caches this object in memory. When promoting to
 * the next batch, it fetches a fresh copy via the TMF-701 GET API.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessFlow {

    private String id;
    private String href;

    @JsonProperty("@type")
    private String type;

    private String state;
    private List<Object> channel;

    @JsonProperty("@baseType")
    private String baseType;

    private List<Object> relatedParty;
    private List<RelatedEntity> relatedEntity;
    /**
     * TMF-701 typed array of {@link TaskFlowRef} pointers to the TaskFlow
     * resources that have been spawned under this processFlow. Populated
     * incrementally as each task in a batch completes — mirrors the legacy
     * Bonita {@code processFlow.addTaskFlowItem(buildTaskFlow(...))} contract
     * so downstream consumers see the same {@code taskFlow[]} array shape.
     */
    private List<TaskFlowRef> taskFlow;
    private List<Characteristic> characteristic;
    private String processFlowSpecification;

    /** Entity reference attached to the processFlow (e.g. diagnostic task). */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelatedEntity {
        private String id;
        private String href;
        private String name;
        private String role;

        @JsonProperty("@type")
        private String type;

        @JsonProperty("@referredType")
        private String referredType;
    }

    /**
     * Key-value characteristic on the processFlow (e.g. peinNumber, SDT
     * Transaction ID, processStatus, statusChangeReason).
     *
     * <p>{@code @type} and {@code @baseType} mirror the legacy Bonita
     * {@code Characteristic.setAtType(STRING) / setAtBaseType(STRING)} pair so
     * the wire payload is byte-identical to the upstream
     * {@code processFlowEntryAction} output. Both fields are NON_NULL-omitted
     * by Jackson when unset, so existing callers that don't populate them stay
     * compatible.</p>
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Characteristic {
        private String id;
        private String name;
        private String value;
        private String valueType;

        @JsonProperty("@type")
        private String type;

        @JsonProperty("@baseType")
        private String baseType;

        private List<Object> characteristicRelationship;
    }
}
