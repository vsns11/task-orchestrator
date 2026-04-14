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
 * TMF-701 processFlow payload published to {@code notification.management}
 * when a processFlow is created.
 *
 * <p>This matches the real TMF-701 processFlowManagement v4 API structure.
 * The pamconsumer reads this, extracts {@code processFlowSpecification} to
 * determine the DAG, and publishes a {@code processFlow.initiated} TaskCommand
 * to the {@code task.command} topic.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessFlowEvent {

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

    /** Key-value characteristic on the processFlow (e.g. peinNumber, SDT Transaction ID). */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Characteristic {
        private String id;
        private String name;
        private String value;
        private String valueType;
        private List<Object> characteristicRelationship;
    }
}
