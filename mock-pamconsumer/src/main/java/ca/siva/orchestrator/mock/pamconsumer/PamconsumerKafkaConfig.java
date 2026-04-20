package ca.siva.orchestrator.mock.pamconsumer;

import ca.siva.orchestrator.dto.tmf.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

import static ca.siva.orchestrator.config.KafkaErrorHandlers.errorHandling;
import static ca.siva.orchestrator.config.KafkaErrorHandlers.logOnceAndSkip;

/**
 * Kafka wiring for the mock pamconsumer — the ONLY module that has any
 * business touching {@code notification.management}.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>{@code notificationKafkaTemplate} — generic-object producer used by
 *       {@code DemoFlowTrigger} to publish synthetic TMF-701 processFlow
 *       events for local demos.</li>
 *   <li>{@code notificationListenerContainerFactory} — consumer factory wired
 *       for {@link NotificationEvent}, used by {@link MockPamConsumer}.</li>
 * </ul>
 *
 * <p>The same log-once-and-skip error handler used by the orchestrator is
 * applied here so poison-pill notifications cannot wedge the pamconsumer
 * partition. All beans are {@code @Profile("local-dev")}-gated so they
 * never load in real environments where the pamconsumer is a separate
 * service.</p>
 */
@Slf4j
@Configuration
@Profile("local-dev")
@EnableConfigurationProperties(PamconsumerProperties.class)
public class PamconsumerKafkaConfig {

    // ---- Producer: notification.management (generic Object) ---------------

    @Bean
    public ProducerFactory<String, Object> notificationProducerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildProducerProperties(null));
        JsonSerializer<Object> jsonSerializer = new JsonSerializer<>(mapper);
        jsonSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(cfg, new StringSerializer(), jsonSerializer);
    }

    @Bean("notificationKafkaTemplate")
    public KafkaTemplate<String, Object> notificationKafkaTemplate(
            ProducerFactory<String, Object> notificationProducerFactory) {
        return new KafkaTemplate<>(notificationProducerFactory);
    }

    // ---- Consumer: notification.management → NotificationEvent -----------

    @Bean
    public ConsumerFactory<String, NotificationEvent> notificationConsumerFactory(
            KafkaProperties kafkaProps, ObjectMapper mapper) {
        Map<String, Object> cfg = new HashMap<>(kafkaProps.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<NotificationEvent> jsonDeser =
                new JsonDeserializer<>(NotificationEvent.class, mapper, false);
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
        factory.setCommonErrorHandler(logOnceAndSkip(log, "mock-pamconsumer"));
        return factory;
    }
}
