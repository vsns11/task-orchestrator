package ca.siva.orchestrator.actionregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response entry from the action-code API ({@code GET /action-codes}).
 * Maps an actionName to its actionCode, along with metadata.
 *
 * <p>Mirrors the real PAM ActionCodeResponse structure.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionCodeEntry {

    @JsonProperty("actionCode")
    private String actionCode;

    @JsonProperty("actionName")
    private String actionName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("flowType")
    private String flowType;

    @JsonProperty("nodeType")
    private String nodeType;
}
