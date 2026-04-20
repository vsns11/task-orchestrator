package ca.siva.orchestrator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Composite primary key for {@link TaskExecution}.
 *
 * <p>Postgres range partitioning (see {@code V3__partition_by_created_at.sql})
 * requires the partition column — here {@code created_at} — to be part of
 * every unique constraint. The <b>flow-task coordinate</b>
 * {@code (correlationId, taskFlowId)} is therefore augmented with
 * {@code createdAt}. Lookups by coordinate alone are exposed by
 * {@link ca.siva.orchestrator.repository.TaskExecutionRepository} via a
 * two-phase (today-partition first, cross-partition fallback) façade.</p>
 *
 * @param correlationId the Kafka correlationId (= TMF-701 processFlow UUID)
 * @param taskFlowId    the TMF-701 taskFlow UUID minted by the task-runner
 * @param createdAt     row creation timestamp — required partition key
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TaskExecutionId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "task_flow_id", length = 64)
    private String taskFlowId;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Coordinate-only constructor — used when constructing a new id from the
     * flow-task pair before persistence sets {@code createdAt}.
     */
    public TaskExecutionId(String correlationId, String taskFlowId) {
        this.correlationId = correlationId;
        this.taskFlowId = taskFlowId;
    }
}
