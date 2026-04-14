package ca.siva.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the task-orchestrator Spring Boot application.
 * Enables Kafka listeners, method-level retry, and async processing.
 */
@EnableKafka
@EnableRetry
@EnableAsync
@SpringBootApplication
public class TaskOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskOrchestratorApplication.class, args);
    }
}
