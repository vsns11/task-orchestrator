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

/**
 * Composite primary key for {@link BatchBarrier}.
 *
 * @param processFlowId the TMF-701 processFlow UUID (= Kafka correlationId)
 * @param batchIndex    the batch position within the DAG (0, 1, 2, ...)
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

    @Column(name = "process_flow_id", length = 64)
    private String processFlowId;

    @Column(name = "batch_index")
    private short batchIndex;
}
