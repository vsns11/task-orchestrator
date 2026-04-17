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
 * <p>Error handling: each listener retries up to 3 times with 500 ms fixed backoff.
 * If processing still fails, {@link ca.siva.orchestrator.kafka.TaskCommandListener}
 * logs the exception and acknowledges the message so the consumer can move on
 * (no dead-letter topic is used).</p>
 *
 * <p><b>Poison-pill handling:</b> both consumer factories wrap their key/value
 * deserializers in {@link ErrorHandlingDeserializer} so malformed records
 * surface as {@link DeserializationException} to the listener container
 * instead of throwing inside {@code poll()} and blocking the partition.
 * {@code DeserializationException} is registered as non-retryable on the
 * {@link DefaultErrorHandler}, so poison pills get <b>zero retries</b> and
 * <b>zero backoff</b>: on the first failure the record is logged once and
 * the offset is committed past it. Retrying a poison pill is pointless —
 * the same bytes will fail the same way every time — and retrying inside
 * {@code poll()} is what produced the original "consumer stuck retrying
 * the same malformed record" loop this config is designed to prevent.
 * (The 500 ms × 3 {@link FixedBackOff} still applies to real listener
 * errors: DB timeouts, downstream failures, etc.)</p>
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
        factory.setCommonErrorHandler(poisonPillAwareErrorHandler());
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
        factory.setCommonErrorHandler(poisonPillAwareErrorHandler());
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
     * {@link DefaultErrorHandler} that treats {@link DeserializationException}
     * as non-retryable: first failure → straight to the recoverer → offset
     * commits → move on. No backoff is applied and no retry is attempted for
     * poison pills. The {@link FixedBackOff}{@code (500ms, 3)} below only
     * applies to exceptions thrown by the listener itself (e.g. DB errors).
     */
    private static DefaultErrorHandler poisonPillAwareErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Skipping poison-pill Kafka record (no retries): topic={} partition={} offset={} key={} cause={}",
                        record.topic(), record.partition(), record.offset(), record.key(),
                        exception.getMessage(), exception),
                new FixedBackOff(500L, 3L));
        // Zero retries for deserialization failures — skip immediately on the
        // first occurrence; never re-poll the same bad bytes.
        handler.addNotRetryableExceptions(DeserializationException.class);
        return handler;
    }
}
