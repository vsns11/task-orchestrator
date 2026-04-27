package ca.siva.orchestrator.dto.tmf;

import ca.siva.orchestrator.dto.tmf.ProcessFlow.Characteristic;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.RelatedEntity;
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
 * Generic wrapper for events on the {@code notification.management} topic.
 *
 * <p>This topic carries two types of events:</p>
 * <ul>
 *   <li>{@code processFlow} — when TMF-701 creates a processFlow ({@code @type = "processFlow"})</li>
 *   <li>{@code TaskFinalAsyncResponseSend} — when an external system completes
 *       an async operation ({@code @type = "TaskFinalAsyncResponseSend"})</li>
 * </ul>
 *
 * <p>The pamconsumer checks {@code @type} to decide how to handle the event.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationEvent {

    // ---- common fields ----
    @JsonProperty("@type")
    private String type;

    @JsonProperty("@baseType")
    private String baseType;

    // ---- processFlow fields (when @type = "processFlow") ----
    private String id;
    private String href;
    private String state;
    private List<Object> channel;
    private List<Object> relatedParty;
    private List<RelatedEntity> relatedEntity;
    private List<Characteristic> characteristic;
    private String processFlowSpecification;

    // ---- async response fields (when @type = "TaskFinalAsyncResponseSend") ----
    private String correlationId;
    private String description;
    private String domain;
    private String eventId;
    private String eventTime;
    private String eventType;
    private String priority;
    private String timeOccurred;
    private String title;
    private List<Object> analyticCharacteristic;
    private AsyncResponseEvent.EventRef event;
    private AsyncResponseEvent.ReportingSystem reportingSystem;
    private String source;

    @JsonProperty("@schemaLocation")
    private String schemaLocation;

    /** Returns true if this is a processFlow.created event from TMF-701. */
    public boolean isProcessFlowCreated() {
        return "processFlow".equalsIgnoreCase(type);
    }

    /** Returns true if this is an async completion event from a downstream system. */
    public boolean isAsyncResponse() {
        return "TaskFinalAsyncResponseSend".equalsIgnoreCase(type);
    }
}
