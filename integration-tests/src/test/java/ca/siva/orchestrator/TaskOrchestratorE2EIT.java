package ca.siva.orchestrator;

import ca.siva.orchestrator.config.TestcontainersConfig;
import ca.siva.orchestrator.domain.BarrierStatus;
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test that verifies the full two-batch DAG flow.
 *
 * <p>This test uses Testcontainers for PostgreSQL and Kafka (no external services),
 * and wires together the orchestrator app + all three mock modules (TMF-701,
 * action registry, task runner) in a single Spring Boot context.</p>
 *
 * <p>Test scenario (Auto_Remediation DAG):</p>
 * <ol>
 *   <li>POST /demo/start triggers a processFlow.initiated event</li>
 *   <li>Orchestrator seeds batch 0 with 2 actions (VOICE_SERVICE_DIAGNOSTIC + INTERNET_CHECK)</li>
 *   <li>MockTaskRunner processes both: SYNC completes immediately, ASYNC goes through WAITING → signal → COMPLETED</li>
 *   <li>When batch 0 closes, orchestrator lazy-seeds batch 1 (NOTIFY_USER)</li>
 *   <li>MockTaskRunner completes batch 1, orchestrator marks flow complete</li>
 * </ol>
 *
 * <p>Assertions verify: 2 CLOSED barrier rows, 3 task execution rows.</p>
 */
@SpringBootTest(
        classes = LocalDevApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class TaskOrchestratorE2EIT extends TestcontainersConfig {

    @Autowired BatchBarrierRepository barrierRepo;
    @Autowired TaskExecutionRepository taskRepo;
    @Autowired TestRestTemplate rest;
    @Value("${local.server.port}") int port;

    @Test
    @SuppressWarnings("unchecked")
    void runs_full_two_batch_flow_to_completion() {
        // Step 1: Trigger a new flow via the demo endpoint
        Map<String, Object> response = rest.postForObject(
                "http://localhost:" + port + "/demo/start", null, Map.class);
        assertThat(response).isNotNull();

        String processFlowId = (String) response.get("processFlowId");
        assertThat(processFlowId).isNotBlank();

        // Step 2: Wait for both barrier rows (batch 0 + batch 1) to reach CLOSED
        await().atMost(ofSeconds(30)).untilAsserted(() -> {
            List<BatchBarrier> all = barrierRepo.findAll().stream()
                    .filter(b -> processFlowId.equals(b.getId().getCorrelationId()))
                    .toList();
            assertThat(all)
                    .as("Expected 2 barrier rows (batch 0 + batch 1)")
                    .hasSize(2);
            assertThat(all)
                    .as("All batches should be CLOSED")
                    .allMatch(b -> b.getStatus() == BarrierStatus.CLOSED);
        });

        // Step 3: Verify 3 task executions (2 in batch 0 + 1 in batch 1)
        long taskCount = taskRepo.findAll().stream()
                .filter(t -> processFlowId.equals(t.getId().getCorrelationId()))
                .count();
        assertThat(taskCount)
                .as("Expected 3 tasks: a1 (ASYNC), a2 (SYNC), a3 (SYNC)")
                .isEqualTo(3);
    }
}
