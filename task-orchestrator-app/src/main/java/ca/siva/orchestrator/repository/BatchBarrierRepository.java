package ca.siva.orchestrator.repository;

import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link BatchBarrier}.
 *
 * <p>A barrier is uniquely identified by its <b>flow-batch coordinate</b>
 * {@code (correlationId, batchIndex)} — the pair we actually key on from
 * Kafka. Because the table is range-partitioned by {@code created_at}
 * (daily), the JPA id additionally carries the partition column; callers
 * should not be forced to know that, so this repository exposes lookups
 * keyed on the coordinate only.</p>
 *
 * <p>Coordinate lookups use a <b>two-phase</b> strategy via {@code default}
 * methods on this interface:
 * <ol>
 *   <li><b>Fast path ({@code …Since})</b> — probe only today's partition
 *       using {@code created_at >= start-of-today-UTC}. Postgres partition
 *       pruning restricts the work to one partition's index.</li>
 *   <li><b>Fallback ({@code …AnyTime})</b> — if the fast path returns empty,
 *       re-run without the date predicate, scanning every live partition's
 *       index. Live partition count is bounded by the pg_cron retention
 *       job, so this fallback touches a small, fixed number of partitions.
 *       </li>
 * </ol>
 *
 * <p>List queries ({@code findAllBy…}) do <b>not</b> use two-phase: "today
 * has some rows" does not imply "today has all rows," so falling back only
 * on empty would silently return partial results for cross-day flows. They
 * always scan across partitions and accept the bounded scan cost.</p>
 */
public interface BatchBarrierRepository extends JpaRepository<BatchBarrier, BatchBarrierId> {

    // ─── Phase 1: today-only (pruned to a single partition) ────────────────

    @Query("SELECT b FROM BatchBarrier b " +
           "WHERE b.id.correlationId = :correlationId " +
           "AND b.id.batchIndex = :batchIndex " +
           "AND b.id.createdAt >= :minCreatedAt")
    Optional<BatchBarrier> findByCorrelationIdAndBatchIndexSince(
            @Param("correlationId") String correlationId,
            @Param("batchIndex") short batchIndex,
            @Param("minCreatedAt") Instant minCreatedAt);

    @Query("SELECT COUNT(b) > 0 FROM BatchBarrier b " +
           "WHERE b.id.correlationId = :correlationId " +
           "AND b.id.batchIndex = :batchIndex " +
           "AND b.id.createdAt >= :minCreatedAt")
    boolean existsByCorrelationIdAndBatchIndexSince(
            @Param("correlationId") String correlationId,
            @Param("batchIndex") short batchIndex,
            @Param("minCreatedAt") Instant minCreatedAt);

    // ─── Phase 2: cross-partition fallback (no date predicate) ────────────

    @Query("SELECT b FROM BatchBarrier b " +
           "WHERE b.id.correlationId = :correlationId " +
           "AND b.id.batchIndex = :batchIndex")
    Optional<BatchBarrier> findByCorrelationIdAndBatchIndexAnyTime(
            @Param("correlationId") String correlationId,
            @Param("batchIndex") short batchIndex);

    @Query("SELECT COUNT(b) > 0 FROM BatchBarrier b " +
           "WHERE b.id.correlationId = :correlationId " +
           "AND b.id.batchIndex = :batchIndex")
    boolean existsByCorrelationIdAndBatchIndexAnyTime(
            @Param("correlationId") String correlationId,
            @Param("batchIndex") short batchIndex);

    /** All barrier rows for a flow — always scans across partitions. */
    @Query("SELECT b FROM BatchBarrier b WHERE b.id.correlationId = :correlationId")
    List<BatchBarrier> findAllByCorrelationId(@Param("correlationId") String correlationId);

    // ─── Public two-phase façade (what callers actually use) ───────────────

    /**
     * Two-phase coordinate lookup: today's partition first, fall back to
     * cross-partition scan on miss. Callers need no knowledge of partitioning.
     */
    default Optional<BatchBarrier> findByCorrelationIdAndBatchIndex(String correlationId, short batchIndex) {
        Optional<BatchBarrier> fast = findByCorrelationIdAndBatchIndexSince(
                correlationId, batchIndex, startOfTodayUtc());
        return fast.isPresent() ? fast : findByCorrelationIdAndBatchIndexAnyTime(correlationId, batchIndex);
    }

    /**
     * Two-phase existence check: today's partition first, fall back on miss.
     * {@code true} from the fast path short-circuits the fallback.
     */
    default boolean existsByCorrelationIdAndBatchIndex(String correlationId, short batchIndex) {
        return existsByCorrelationIdAndBatchIndexSince(correlationId, batchIndex, startOfTodayUtc())
                || existsByCorrelationIdAndBatchIndexAnyTime(correlationId, batchIndex);
    }

    /**
     * Start of the current day in UTC. Matches the daily partition boundaries
     * created by the partition-maintenance routine (UTC midnight) so the
     * predicate prunes to exactly one partition.
     */
    private static Instant startOfTodayUtc() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }
}
