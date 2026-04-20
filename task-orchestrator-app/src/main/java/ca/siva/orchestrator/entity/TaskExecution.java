package ca.siva.orchestrator.entity;

import ca.siva.orchestrator.domain.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Per-task audit trail recording the lifecycle of each action execution.
 *
 * <p>Each row represents a task execution (identified by the composite
 * key of correlationId, taskFlowId, and createdAt — the last being the
 * partition key required by the range-partitioned table).</p>
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private TaskStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Serialized downstream result. Stored as unbounded {@code TEXT}
     * (Postgres {@code TEXT} has no length limit). Any size cap should be
     * enforced at the database layer, not in application code.
     */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    /** Serialized error payload. Unbounded {@code TEXT}. */
    @Column(name = "error_json", columnDefinition = "TEXT")
    private String errorJson;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * Ensures the partition key ({@code id.createdAt}) is populated before
     * insert — same reason as {@link BatchBarrier#prePersist()}.
     */
    @PrePersist
    void prePersist() {
        if (id != null && id.getCreatedAt() == null) {
            id.setCreatedAt(Instant.now());
        }
    }

    /** Read-only accessor mirroring {@code id.createdAt} for convenience. */
    public Instant getCreatedAt() {
        return id == null ? null : id.getCreatedAt();
    }
}
