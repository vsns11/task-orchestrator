-- Covering index for BarrierService.resolveDependencies() query:
-- SELECT te FROM TaskExecution te
-- WHERE te.id.processFlowId = ? AND te.actionName = ? AND te.status = 'COMPLETED'
-- ORDER BY te.createdAt DESC LIMIT 1
CREATE INDEX idx_task_execution_dep_lookup
    ON task_execution (process_flow_id, action_name, status, created_at DESC);
