package ca.siva.orchestrator.domain;

/** Kafka message type — determines how the message should be interpreted. */
public enum MessageType {
    EVENT,
    COMMAND,
    SIGNAL
}
