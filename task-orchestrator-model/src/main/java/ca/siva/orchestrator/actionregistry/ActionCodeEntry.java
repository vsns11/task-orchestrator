package ca.siva.orchestrator.actionregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response entry from the action-code API
 * ({@code GET /availableActions/findAllAvailableActionsFromDb}).
 *
 * <p>Real API shape (April 2025):</p>
 * <pre>
 * {
 *   "actionCode":       "3022",
 *   "name":             "Email Pickup Modem in Store",
 *   "enabled":          true,
 *   "actionType":       "MS",
 *   "description":      "Email Pickup Modem in Store",
 *   "createdTimeStamp": "2025-04-08T21:07:51.883707707Z"
 * }
 * </pre>
 *
 * <p>Important: the API field is {@code "name"} — NOT {@code "actionName"}.
 * The Java field is still called {@code actionName} internally for clarity
 * at call sites (and because it's paired with {@code actionCode} /
 * {@code dcxActionCode} in the rest of the codebase) — but the Jackson
 * binding uses {@code @JsonProperty("name")} so the real payload
 * deserializes correctly. Getting this wrong was the root cause of the
 * NPE in {@link ActionRegistry#loadActionCodes(org.springframework.web.client.RestClient)}
 * — every row came back with a null name and {@code ConcurrentHashMap.put}
 * blew up.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionCodeEntry {

    /** Numeric action code string (e.g. {@code "3022"}, {@code "7001"}). */
    @JsonProperty("actionCode")
    private String actionCode;

    /** Human-readable action name. Keys the registry's primary map. */
    @JsonProperty("name")
    private String actionName;

    /** Whether this action is currently enabled in the registry. */
    @JsonProperty("enabled")
    private Boolean enabled;

    /** Action type tag — typically {@code MS}, {@code CDA}, {@code Kickout}, etc. */
    @JsonProperty("actionType")
    private String actionType;

    @JsonProperty("description")
    private String description;

    @JsonProperty("createdTimeStamp")
    private String createdTimeStamp;
}
