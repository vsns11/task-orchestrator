package ca.siva.orchestrator.actionregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response entry from the DCX action-code API ({@code GET /dcx-action-codes}).
 *
 * <p>The {@code parent} field is the actionCode — this is the join key to
 * link a dcxActionCode back to an actionCode. The lookup chain is:</p>
 * <ol>
 *   <li>actionName → ActionCodeEntry → get actionCode</li>
 *   <li>actionCode (= parent) → DcxActionCodeEntry → get dcxActionCode</li>
 * </ol>
 *
 * <p>Mirrors the real PAM DcxActionCodeResponse structure.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DcxActionCodeEntry {

    /** The actionCode this dcxActionCode belongs to (join key). */
    @JsonProperty("parent")
    private String parent;

    @JsonProperty("flowType")
    private String flowType;

    @JsonProperty("dcxActionCode")
    private String dcxActionCode;

    @JsonProperty("nodeType")
    private String nodeType;
}
