package ca.siva.orchestrator.repository;

import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * Batch-loads all COMPLETED executions for a given processFlowId and a set of actionNames.
     * Used when seeding a batch with multiple actions that each declare {@code dependsOn}.
     * Returns one row per (processFlowId, actionName); the caller is responsible for
     * picking the latest if duplicates exist (e.g. via {@code findLatest...} post-filter).
     *
     * <p>Single query replaces N individual {@code findLatestCompletedByProcessFlowAndAction}
     * calls — important when a batch depends on multiple prior actions.</p>
     */
    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.processFlowId = :processFlowId " +
           "AND te.actionName IN :actionNames " +
           "AND te.status = 'COMPLETED' " +
           "AND te.resultJson IS NOT NULL " +
           "ORDER BY te.createdAt DESC")
    List<TaskExecution> findCompletedByProcessFlowAndActionNames(
            @Param("processFlowId") String processFlowId,
            @Param("actionNames") Collection<String> actionNames);
}
