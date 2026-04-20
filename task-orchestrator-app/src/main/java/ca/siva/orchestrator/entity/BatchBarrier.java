package ca.siva.orchestrator.entity;

import ca.siva.orchestrator.domain.BarrierStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Tracks batch-level progress for a single process flow.
 *
 * <p>At most one row per {@code (correlationId, batchIndex)} exists at any time
 * (lazy seeding). The barrier closes when {@code pending() == 0}, triggering
 * promotion of the next batch.</p>
 *
 * <p>Concurrency is guarded by JPA {@link Version @Version} optimistic locking
 * combined with {@code @Retryable} at the service layer.</p>
 *
 * <p>The table is range-partitioned by {@code created_at} (see
 * {@code V3__partition_by_created_at.sql}), so {@code createdAt} is part of
 * the composite primary key in {@link BatchBarrierId}.</p>
 */
@Entity
@Table(name = "batch_barrier")
@Getter
@Setter
@NoArgsConstructor
public class BatchBarrier {

    @EmbeddedId
    private BatchBarrierId id;

    @Column(name = "dag_key", length = 64)
    private String dagKey;

    @Column(name = "task_total")
    private int taskTotal;

    @Column(name = "task_completed")
    private int taskCompleted;

    @Column(name = "task_failed")
    private int taskFailed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private BarrierStatus status;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * Ensures the partition key ({@code id.createdAt}) is populated before
     * insert. Hibernate's @CreationTimestamp doesn't apply to @EmbeddedId
     * fields, and the partition key must be non-null at insert time,
     * otherwise the row can't be routed to any partition.
     */
    @PrePersist
    void prePersist() {
        if (id != null && id.getCreatedAt() == null) {
            id.setCreatedAt(Instant.now());
        }
    }

    /** Read-only accessor mirroring {@code id.createdAt} for callers that don't reach into the ID. */
    public Instant getCreatedAt() {
        return id == null ? null : id.getCreatedAt();
    }

    // ---- domain methods ----

    /** Number of tasks still outstanding in this batch. */
    public int pending() {
        return taskTotal - taskCompleted - taskFailed;
    }

    /** Transition to OPEN status with current timestamp. */
    public void open() {
        this.status = BarrierStatus.OPEN;
        this.openedAt = Instant.now();
    }

    /** Transition to CLOSED status with current timestamp. */
    public void close() {
        this.status = BarrierStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    /** Transition to FAILED status. */
    public void fail() {
        this.status = BarrierStatus.FAILED;
        this.closedAt = Instant.now();
    }

    /** Increment completed task counter. */
    public void incCompleted() {
        this.taskCompleted++;
    }

    /** Increment failed task counter. */
    public void incFailed() {
        this.taskFailed++;
    }
}
