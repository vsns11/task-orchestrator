package ca.siva.orchestrator.actionregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response entry from the DCX action-code API
 * ({@code GET /dcxActionCodes/findAllDcxActionCodesFromDb}).
 *
 * <p>Real API shape (April 2025):</p>
 * <pre>
 * {
 *   "parent":        "3018",
 *   "dcxActionCode": "3018",
 *   "modemType":     "DEFAULT",
 *   "flowType":      "DEFAULT"
 * }
 * </pre>
 *
 * <p>The {@code parent} field is the actionCode this dcxActionCode belongs
 * to — join key for the lookup chain:</p>
 * <ol>
 *   <li>actionName → {@link ActionCodeEntry} → get actionCode</li>
 *   <li>actionCode (= {@link #parent}) → {@link DcxActionCodeEntry} → get dcxActionCode</li>
 * </ol>
 *
 * <p>{@code modemType} and {@code flowType} default to {@code "DEFAULT"} for
 * the common case. When the real DCX registry contains multiple rows for
 * the same {@code parent} differentiated by these two dimensions, the
 * registry's simple 1-D map still works — it returns the first-seen row
 * and logs a duplicate warning for the others. Extend the key with
 * {@code parent + "," + flowType + "," + modemType} (per upstream
 * {@code ActionBuilder.buildKeyForDcxActionCode}) if that becomes a
 * real constraint.</p>
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

    @JsonProperty("dcxActionCode")
    private String dcxActionCode;

    /** Modem-type discriminator — commonly {@code "DEFAULT"}. */
    @JsonProperty("modemType")
    private String modemType;

    /** Flow-type discriminator — commonly {@code "DEFAULT"}. */
    @JsonProperty("flowType")
    private String flowType;
}
