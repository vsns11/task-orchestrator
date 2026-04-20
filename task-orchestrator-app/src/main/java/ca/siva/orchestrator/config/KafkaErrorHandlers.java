package ca.siva.orchestrator.config;

import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Shared Kafka error-handling helpers used by every Kafka-consuming module
 * (orchestrator, mock-pamconsumer). Extracted to kill the previously
 * duplicated {@code errorHandling(...)} wrapper and
 * {@code logOnceAndSkipErrorHandler()} factory that lived in both
 * {@code KafkaConfig} and {@code PamconsumerKafkaConfig}.
 *
 * <p><b>Policy: log once, skip, never retry.</b>
 * Every failure — whether a malformed record surfaced as
 * {@link DeserializationException} or a listener exception (DB error, NPE,
 * downstream timeout, …) — is handled the same way: log one ERROR line with
 * the record's topic/partition/offset/key and the root cause, commit the
 * offset past it, and move on. No retries, no DLT. Poison pills do not
 * stall partitions.</p>
 */
public final class KafkaErrorHandlers {

    private KafkaErrorHandlers() {
        // utility
    }

    /**
     * Wraps any delegate {@link Deserializer} in an
     * {@link ErrorHandlingDeserializer} so deserialization exceptions are
     * converted into {@link DeserializationException} records the listener
     * container can handle — instead of throwing inside
     * {@code KafkaConsumer.poll()} and stalling the partition.
     */
    public static <T> ErrorHandlingDeserializer<T> errorHandling(Deserializer<T> delegate) {
        return new ErrorHandlingDeserializer<>(delegate);
    }

    /**
     * {@link DefaultErrorHandler} configured for zero retries on <b>every</b>
     * exception type. {@code FixedBackOff(0, 0)} means interval 0 ms,
     * max attempts 0 — on the first failure, control goes straight to the
     * recoverer which logs one ERROR line and lets the container commit past
     * the record.
     *
     * @param log       logger to emit the error line on
     * @param logPrefix free-form prefix to distinguish modules in logs
     *                  (e.g. {@code "orchestrator"}, {@code "mock-pamconsumer"})
     */
    public static DefaultErrorHandler logOnceAndSkip(Logger log, String logPrefix) {
        return new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "[{}] Skipping un-processable Kafka record (no retries): "
                                + "topic={} partition={} offset={} key={} cause={}",
                        logPrefix,
                        record.topic(), record.partition(), record.offset(), record.key(),
                        exception.getMessage(), exception),
                new FixedBackOff(0L, 0L));
    }
}
