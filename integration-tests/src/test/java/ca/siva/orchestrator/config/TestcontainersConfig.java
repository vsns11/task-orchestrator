package ca.siva.orchestrator.config;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers lifecycle for integration tests.
 *
 * <p>Starts singleton PostgreSQL and Kafka containers once (via {@code static {}} block)
 * and reuses them across all test classes that extend this base class.
 * {@link DynamicPropertySource} injects the container endpoints into Spring's
 * environment so that the datasource and Kafka bootstrap-servers are configured
 * automatically.</p>
 *
 * <p>This avoids the overhead of starting fresh containers per test class.</p>
 */
public abstract class TestcontainersConfig {

    /** Shared PostgreSQL container — postgres:16-alpine with test credentials. */
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orchestrator_test")
                    .withUsername("test")
                    .withPassword("test");

    /** Shared Kafka container — Confluent CP-Kafka 7.6 in KRaft mode. */
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    // Start containers once and share across all test classes
    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /**
     * Injects container connection details into Spring's property sources.
     * Called by the Spring test framework before context initialization.
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
