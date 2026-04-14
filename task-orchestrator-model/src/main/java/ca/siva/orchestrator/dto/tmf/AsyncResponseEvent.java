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
 * External async response event published to {@code notification.management}
 * when a downstream system completes an async operation.
 *
 * <p>This matches the real {@code TaskFinalAsyncResponseSend} event structure.
 * The pamconsumer reads this, correlates by {@code event.id} (the downstreamTransactionId),
 * looks up the waiting task, and publishes a {@code task.signal} TaskCommand
 * to the {@code task.command} topic.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsyncResponseEvent {

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
    private EventRef event;
    private List<Object> relatedParty;
    private ReportingSystem reportingSystem;
    private String source;

    @JsonProperty("@baseType")
    private String baseType;

    @JsonProperty("@schemaLocation")
    private String schemaLocation;

    @JsonProperty("@type")
    private String type;

    /**
     * Reference to the downstream result — {@code id} is the downstreamTransactionId
     * used to correlate with the waiting task, {@code href} is the URL to
     * fetch the result from the downstream system.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventRef {
        private String id;
        private String href;
        private List<Object> callerIdentifier;
    }

    /** The external system that produced this async response (e.g. ACUT). */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportingSystem {
        private String id;
        private String href;
        private String name;

        @JsonProperty("@baseType")
        private String baseType;

        @JsonProperty("@schemaLocation")
        private String schemaLocation;

        @JsonProperty("@type")
        private String type;

        @JsonProperty("@referredType")
        private String referredType;
    }
}
