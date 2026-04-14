package ca.siva.orchestrator.domain;

/**
 * Lifecycle states for a {@code batch_barrier} row.
 *
 * <ul>
 *   <li>{@link #OPEN} — batch is actively receiving task events</li>
 *   <li>{@link #CLOSED} — all tasks completed; next batch can be seeded</li>
 *   <li>{@link #FAILED} — a non-retryable failure terminated this batch</li>
 * </ul>
 */
public enum BarrierStatus {
    OPEN, CLOSED, FAILED
}
