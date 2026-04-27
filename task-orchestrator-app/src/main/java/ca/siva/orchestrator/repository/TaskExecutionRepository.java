package ca.siva.orchestrator.repository;

import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TaskExecution}.
 *
 * <p>A task execution is uniquely identified by its <b>flow-task coordinate</b>
 * {@code (correlationId, taskFlowId)} — the pair we actually key on from
 * Kafka. Because the table is range-partitioned by {@code created_at}
 * (daily), the JPA id additionally carries the partition column; callers
 * should not be forced to know that, so this repository exposes lookups
 * keyed on the coordinate only.</p>
 *
 * <p>Single-row coordinate lookups use a <b>two-phase</b> strategy: probe
 * today's partition first (pruned to one) via {@code …Since}, fall back to
 * a cross-partition scan ({@code …AnyTime}) only on miss. List queries
 * always scan across partitions — "today has some rows" does not imply
 * "today has all rows," and falling back only on empty would silently
 * return partial results for cross-day flows.</p>
 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, TaskExecutionId> {

    // ─── Phase 1: today-only (pruned to a single partition) ────────────────

    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.correlationId = :correlationId " +
           "AND te.id.taskFlowId = :taskFlowId " +
           "AND te.id.createdAt >= :minCreatedAt")
    Optional<TaskExecution> findByCorrelationIdAndTaskFlowIdSince(
            @Param("correlationId") String correlationId,
            @Param("taskFlowId") String taskFlowId,
            @Param("minCreatedAt") Instant minCreatedAt);

    // ─── Phase 2: cross-partition fallback (no date predicate) ────────────

    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.correlationId = :correlationId " +
           "AND te.id.taskFlowId = :taskFlowId")
    Optional<TaskExecution> findByCorrelationIdAndTaskFlowIdAnyTime(
            @Param("correlationId") String correlationId,
            @Param("taskFlowId") String taskFlowId);

    // ─── Always-scan list queries (correctness > pruning) ─────────────────

    /**
     * Latest COMPLETED execution for a flow + actionName. Always scans across
     * partitions: a COMPLETED dependency may have been written on an earlier
     * day than the caller is running on.
     */
    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.correlationId = :correlationId " +
           "AND te.actionName = :actionName " +
           "AND te.status = 'COMPLETED' " +
           "AND te.resultJson IS NOT NULL " +
           "ORDER BY te.id.createdAt DESC " +
           "LIMIT 1")
    Optional<TaskExecution> findLatestCompletedByCorrelationIdAndActionName(
            @Param("correlationId") String correlationId,
            @Param("actionName") String actionName);

    /**
     * All COMPLETED executions for a flow + a set of actionNames, latest first.
     * Always scans across partitions — a batch's declared dependencies may be
     * resolved from prior batches that completed on earlier days.
     */
    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.correlationId = :correlationId " +
           "AND te.actionName IN :actionNames " +
           "AND te.status = 'COMPLETED' " +
           "AND te.resultJson IS NOT NULL " +
           "ORDER BY te.id.createdAt DESC")
    List<TaskExecution> findCompletedByCorrelationIdAndActionNames(
            @Param("correlationId") String correlationId,
            @Param("actionNames") Collection<String> actionNames);

    /**
     * All task-execution rows for a given flow + batch. Drives both the
     * kickout sweep ({@link ca.siva.orchestrator.service.BatchKickoutEvaluator})
     * AND the final-state PATCH cascade
     * ({@link ca.siva.orchestrator.service.ProcessFlowCompletionService}) —
     * the load happens exactly once per batch close in
     * {@link ca.siva.orchestrator.service.BatchResultsLoader} and the same
     * snapshot serves both consumers. The flow-wide variant
     * ({@code findAllByCorrelationId}) was removed because end-of-flow
     * cascades over the closing batch's responses are equivalent: every
     * prior batch was promoted on a clean kickout sweep, so its taskStatus
     * codes were PASSED and contribute nothing to the priority cascade.
     */
    @Query("SELECT te FROM TaskExecution te " +
           "WHERE te.id.correlationId = :correlationId " +
           "AND te.batchIndex = :batchIndex")
    List<TaskExecution> findAllByCorrelationIdAndBatchIndex(
            @Param("correlationId") String correlationId,
            @Param("batchIndex") short batchIndex);

    // ─── Public two-phase façade (what callers actually use) ───────────────

    /**
     * Two-phase coordinate lookup: today's partition first, fall back to
     * cross-partition scan on miss. Callers need no knowledge of partitioning.
     */
    default Optional<TaskExecution> findByCorrelationIdAndTaskFlowId(String correlationId, String taskFlowId) {
        Optional<TaskExecution> fast = findByCorrelationIdAndTaskFlowIdSince(
                correlationId, taskFlowId, startOfTodayUtc());
        return fast.isPresent() ? fast : findByCorrelationIdAndTaskFlowIdAnyTime(correlationId, taskFlowId);
    }

    /**
     * Start of the current day in UTC. Matches the daily partition boundaries
     * so the predicate prunes to exactly one partition.
     */
    private static Instant startOfTodayUtc() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }
}
