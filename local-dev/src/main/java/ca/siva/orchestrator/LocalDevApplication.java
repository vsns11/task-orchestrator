package ca.siva.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Local development entry point.
 *
 * <p>Starts the orchestrator app with all mock services in a single JVM:</p>
 * <ul>
 *   <li>MockTmf701Controller — mock TMF-701 processFlow API</li>
 *   <li>MockActionRegistryController — mock action-code registry</li>
 *   <li>MockPamConsumer — listens on notification.management, publishes to task.command</li>
 *   <li>MockTaskRunner — executes tasks, simulates async callbacks</li>
 *   <li>DemoFlowTrigger — POST /demo/start to kick off flows</li>
 * </ul>
 *
 * <p>All classes share the {@code ca.siva.orchestrator} base package, so
 * Spring Boot's component scan picks up everything automatically.</p>
 *
 * <p>Prerequisites: {@code make docker-up} (starts PostgreSQL + Kafka)</p>
 */
@EnableKafka
@EnableRetry
@EnableAsync
@SpringBootApplication
public class LocalDevApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalDevApplication.class, args);
    }
}
