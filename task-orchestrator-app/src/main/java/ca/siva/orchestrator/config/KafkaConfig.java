package ca.siva.orchestrator.config;

import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka infrastructure configuration.
 *
 * <p>Topics ({@code task.command}, {@code notification.management}) are provisioned
 * by a separate team and are assumed to exist before this app starts. This class
 * does NOT declare any {@code NewTopic} beans — no topic creation is attempted.</p>
 *
 * <p>Two listener container factories:</p>
 * <ul>
 *   <li>{@code kafkaListenerContainerFactory} — for {@code task.command} topic,
 *       deserializes into {@link TaskCommand}</li>
 *   <li>{@code notificationListenerContainerFactory} — for {@code notification.management} topic,
 *       deserializes into {@link NotificationEvent}</li>
 * </ul>
 *
 * <p><b>Error handling policy: log once, skip, never retry.</b>
 * Every failure — whether a malformed record surfaced as
 * {@link DeserializationException} or a listener exception (DB error, NPE,
 * downstream timeout, …) — is handled the same way: log one ERROR line with
 * the record's topic/partition/offset/key and the root cause, commit the
 * offset past it, and move on. No {@link FixedBackOff} retries, no DLT.</p>
 *
 * <p>Both consumer factories wrap their key/value deserializers in
 * {@link ErrorHandlingDeserializer} so deserialization failures surface as
 * records the listener container can handle, instead of throwing inside
 * {@code KafkaConsumer.poll()} and stalling the partition.</p>
 */
@Slf4j
@Configuration
public class KafkaConfig {

    // ---- Producer (task.command topic) ----

    @Bean
    public ProducerFactory<String, TaskCommand> producerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildProducerProperties(null));
        JsonSerializer<TaskCommand> jsonSerializer = new JsonSerializer<>(mapper);
        jsonSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(cfg, new StringSerializer(), jsonSerializer);
    }

    @Bean
    public KafkaTemplate<String, TaskCommand> kafkaTemplate(
            ProducerFactory<String, TaskCommand> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

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

        JsonDeserializer<TaskCommand> jsonDeser = new JsonDeserializer<>(TaskCommand.class, mapper, false);
        jsonDeser.addTrustedPackages("ca.siva.orchestrator.dto");
        jsonDeser.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                cfg,
                errorHandling(new StringDeserializer()),
                errorHandling(jsonDeser));
    }

    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TaskCommand>
    kafkaListenerContainerFactory(ConsumerFactory<String, TaskCommand> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TaskCommand> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(logOnceAndSkipErrorHandler());
        return factory;
    }

    // ---- Consumer: notification.management topic → NotificationEvent ----

    @Bean
    public ConsumerFactory<String, NotificationEvent> notificationConsumerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<NotificationEvent> jsonDeser = new JsonDeserializer<>(NotificationEvent.class, mapper, false);
        jsonDeser.addTrustedPackages("ca.siva.orchestrator.dto.tmf");
        jsonDeser.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                cfg,
                errorHandling(new StringDeserializer()),
                errorHandling(jsonDeser));
    }

    @Bean(name = "notificationListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent>
    notificationListenerContainerFactory(
            ConsumerFactory<String, NotificationEvent> notificationConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(logOnceAndSkipErrorHandler());
        return factory;
    }

    // ---- helpers ----

    /**
     * Wrap any delegate {@link Deserializer} in an {@link ErrorHandlingDeserializer}
     * so deserialization exceptions are converted into {@link DeserializationException}
     * records the listener container can handle — instead of throwing inside
     * {@code KafkaConsumer.poll()} and stalling the partition.
     */
    private static <T> ErrorHandlingDeserializer<T> errorHandling(Deserializer<T> delegate) {
        return new ErrorHandlingDeserializer<>(delegate);
    }

    /**
     * {@link DefaultErrorHandler} configured for zero retries on <b>every</b>
     * exception type. The {@code FixedBackOff(0, 0)} means: interval 0 ms,
     * max attempts 0 — on the first failure, control goes straight to the
     * recoverer, which logs one ERROR line and lets the container commit
     * past the record. Applies equally to {@link DeserializationException}
     * (poison pills) and to anything the listener itself throws
     * (DB errors, NPE, downstream timeouts, …).
     */
    private static DefaultErrorHandler logOnceAndSkipErrorHandler() {
        return new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Skipping un-processable Kafka record (no retries): topic={} partition={} offset={} key={} cause={}",
                        record.topic(), record.partition(), record.offset(), record.key(),
                        exception.getMessage(), exception),
                new FixedBackOff(0L, 0L));
    }
}
