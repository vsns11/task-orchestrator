package ca.siva.orchestrator.config;

import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka infrastructure configuration for both topics.
 *
 * <p>Two listener container factories:</p>
 * <ul>
 *   <li>{@code kafkaListenerContainerFactory} — for {@code task.command} topic,
 *       deserializes into {@link TaskCommand}</li>
 *   <li>{@code notificationListenerContainerFactory} — for {@code notification.management} topic,
 *       deserializes into {@link NotificationEvent} (TMF-701 native payloads)</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    // ---- Topics ----

    @Bean
    public NewTopic taskCommandTopic(TopicsProperties props) {
        return TopicBuilder.name(props.taskCommand())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationManagementTopic(TopicsProperties props) {
        return TopicBuilder.name(props.notificationManagement())
                .partitions(3)
                .replicas(1)
                .build();
    }

    // ---- Producer (task.command topic) ----

    /**
     * Producer factory for TaskCommand messages.
     * Disables type headers so messages are clean JSON readable by any tool.
     */
    @Bean
    public ProducerFactory<String, TaskCommand> producerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildProducerProperties(null));
        JsonSerializer<TaskCommand> jsonSerializer = new JsonSerializer<TaskCommand>(mapper);
        jsonSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(cfg, new StringSerializer(), jsonSerializer);
    }

    @Bean
    public KafkaTemplate<String, TaskCommand> kafkaTemplate(
            ProducerFactory<String, TaskCommand> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Generic producer factory for publishing any object as JSON.
     * Used by DemoFlowTrigger to publish NotificationEvent to notification.management.
     */
    @Bean
    public ProducerFactory<String, Object> genericProducerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildProducerProperties(null));
        JsonSerializer<Object> jsonSerializer = new JsonSerializer<>(mapper);
        jsonSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(cfg, new StringSerializer(), jsonSerializer);
    }

    @Bean("notificationKafkaTemplate")
    public KafkaTemplate<String, Object> notificationKafkaTemplate(
            ProducerFactory<String, Object> genericProducerFactory) {
        return new KafkaTemplate<>(genericProducerFactory);
    }

    // ---- Consumer: task.command topic → TaskCommand ----

    @Bean
    public ConsumerFactory<String, TaskCommand> consumerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        JsonDeserializer<TaskCommand> valueDeser = new JsonDeserializer<>(TaskCommand.class, mapper, false);
        valueDeser.addTrustedPackages("ca.siva.orchestrator.dto");
        valueDeser.setUseTypeHeaders(false);
        return new DefaultKafkaConsumerFactory<>(cfg, new StringDeserializer(), valueDeser);
    }

    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TaskCommand>
    kafkaListenerContainerFactory(ConsumerFactory<String, TaskCommand> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TaskCommand> factory = new ConcurrentKafkaListenerContainerFactory<String, TaskCommand>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 3L)));
        return factory;
    }

    // ---- Consumer: notification.management topic → NotificationEvent ----

    @Bean
    public ConsumerFactory<String, NotificationEvent> notificationConsumerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        JsonDeserializer<NotificationEvent> valueDeser = new JsonDeserializer<>(NotificationEvent.class, mapper, false);
        valueDeser.addTrustedPackages("ca.siva.orchestrator.dto.tmf");
        valueDeser.setUseTypeHeaders(false);
        return new DefaultKafkaConsumerFactory<>(cfg, new StringDeserializer(), valueDeser);
    }

    @Bean(name = "notificationListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>
    notificationListenerContainerFactory(
            ConsumerFactory<String, NotificationEvent> notificationConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> factory = new ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>();
        factory.setConsumerFactory(notificationConsumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 3L)));
        return factory;
    }
}
