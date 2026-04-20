package ca.siva.orchestrator.config;

import ca.siva.orchestrator.dto.TaskCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

import static ca.siva.orchestrator.config.KafkaErrorHandlers.errorHandling;
import static ca.siva.orchestrator.config.KafkaErrorHandlers.logOnceAndSkip;

/**
 * Kafka infrastructure configuration for the orchestrator.
 *
 * <p>The orchestrator listens on a SINGLE topic — {@code task.command} —
 * for every shape of message it consumes. The {@code notification.management}
 * topic is a pamconsumer concern; beans that read/write it live in the
 * {@code mock-pamconsumer} module, not here.</p>
 *
 * <p>Topics are provisioned by a separate team and assumed to exist before
 * this app starts — no {@code NewTopic} beans are declared.</p>
 *
 * <p><b>Error handling policy</b> — log once, skip, never retry. Poison
 * pills surface as {@link DeserializationException} instead of throwing
 * inside {@code KafkaConsumer.poll()}. See {@link KafkaErrorHandlers} for
 * the shared {@code errorHandling(...)} wrapper and {@code logOnceAndSkip}
 * factory applied here.</p>
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
        factory.setCommonErrorHandler(logOnceAndSkip(log, "orchestrator"));
        return factory;
    }
}
