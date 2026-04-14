package ca.siva.orchestrator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Per-task audit trail recording the lifecycle of each action execution.
 *
 * <p>Each row represents a single attempt of a task (identified by the composite
 * key of correlationId, flowId, taskId, and attempt number).</p>
 */
@Entity
@Table(name = "task_execution")
@Getter
@Setter
@NoArgsConstructor
public class TaskExecution {

    @EmbeddedId
    private TaskExecutionId id;

    @Column(name = "action_name", length = 64)
    private String actionName;

    @Column(name = "action_code", length = 64)
    private String actionCode;

    @Column(name = "batch_index")
    private short batchIndex;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "downstream_id", length = 256)
    private String downstreamId;

    @Column(name = "downstream_href", length = 1024)
    private String downstreamHref;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_json", columnDefinition = "TEXT")
    private String errorJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;
}
