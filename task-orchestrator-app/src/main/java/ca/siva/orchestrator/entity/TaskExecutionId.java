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
 * Composite primary key for {@link TaskExecution}.
 *
 * @param processFlowId the TMF-701 processFlow UUID (= Kafka correlationId)
 * @param taskFlowId    the TMF-701 taskFlow UUID minted by the task-runner
 * @param attempt       the retry attempt number (1-based)
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

    @Column(name = "process_flow_id", length = 64)
    private String processFlowId;

    @Column(name = "task_flow_id", length = 64)
    private String taskFlowId;

    @Column(name = "attempt")
    private short attempt;
}
