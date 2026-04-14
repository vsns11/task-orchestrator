package ca.siva.orchestrator.entity;

import ca.siva.orchestrator.domain.BarrierStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

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
