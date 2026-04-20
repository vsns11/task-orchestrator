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
 * Composite primary key for {@link BatchBarrier}.
 *
 * <p>Postgres range partitioning (see {@code V3__partition_by_created_at.sql})
 * requires the partition column — here {@code created_at} — to be part of
 * every unique constraint, including the primary key. The <b>flow-batch
 * coordinate</b> {@code (correlationId, batchIndex)} is therefore augmented
 * with {@code createdAt} to satisfy the constraint. Lookups by coordinate
 * alone are exposed by
 * {@link ca.siva.orchestrator.repository.BatchBarrierRepository} via a
 * two-phase (today-partition first, cross-partition fallback) façade.</p>
 *
 * @param correlationId the Kafka correlationId (= TMF-701 processFlow UUID)
 * @param batchIndex    the batch position within the DAG (0, 1, 2, ...)
 * @param createdAt     row creation timestamp — required partition key
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class BatchBarrierId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "batch_index")
    private short batchIndex;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Coordinate-only constructor — used when constructing a new id from the
     * flow-batch pair before persistence sets {@code createdAt}.
     */
    public BatchBarrierId(String correlationId, short batchIndex) {
        this.correlationId = correlationId;
        this.batchIndex = batchIndex;
    }
}
