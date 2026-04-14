package ca.siva.orchestrator.repository;

import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, TaskExecutionId> {

    /**
     * Finds the latest COMPLETED task execution for a given processFlowId and actionName.
     * Used by BarrierService to resolve action dependencies when building
     * task.execute commands for the next batch.
     */
    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.processFlowId = :processFlowId " +
           "AND te.actionName = :actionName " +
           "AND te.status = 'COMPLETED' " +
           "AND te.resultJson IS NOT NULL " +
           "ORDER BY te.createdAt DESC " +
           "LIMIT 1")
    Optional<TaskExecution> findLatestCompletedByProcessFlowAndAction(
            @Param("processFlowId") String processFlowId,
            @Param("actionName") String actionName);
}
