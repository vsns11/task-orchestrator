package ca.siva.orchestrator.demo;

import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.mock.pamconsumer.PamconsumerProperties;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demo controller for triggering flows and manually injecting signals.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /demo/start} — triggers a new flow via notification.management</li>
 *   <li>{@code POST /demo/signal} — manually injects a task.signal for an ASYNC task</li>
 *   <li>{@code GET /demo/barriers} — inspect batch_barrier table</li>
 *   <li>{@code GET /demo/tasks} — inspect task_execution table</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/demo")
@Profile("local-dev")
public class DemoFlowTrigger {

    private final KafkaTemplate<String, Object> notificationKafka;
    private final TaskCommandPublisher          taskCommandPublisher;
    private final TaskCommandFactory            taskCommandFactory;
    private final PamconsumerProperties         pamconsumerProps;
    private final BatchBarrierRepository        barrierRepo;
    private final TaskExecutionRepository       taskRepo;

    public DemoFlowTrigger(
            @Qualifier("notificationKafkaTemplate") KafkaTemplate<String, Object> notificationKafka,
            TaskCommandPublisher taskCommandPublisher,
            TaskCommandFactory taskCommandFactory,
            PamconsumerProperties pamconsumerProps,
            BatchBarrierRepository barrierRepo,
            TaskExecutionRepository taskRepo) {
        this.notificationKafka = notificationKafka;
        this.taskCommandPublisher = taskCommandPublisher;
        this.taskCommandFactory = taskCommandFactory;
        this.pamconsumerProps = pamconsumerProps;
        this.barrierRepo = barrierRepo;
        this.taskRepo = taskRepo;
    }

    /**
     * Triggers a new flow by publishing a real TMF-701 processFlow payload
     * to the notification.management topic.
     */
    @PostMapping("/start")
    public Map<String, Object> start(
            @RequestParam(defaultValue = "Auto_Remediation") String dagKey) {

        String processFlowId = UUID.randomUUID().toString();

        ProcessFlow processFlow = ProcessFlow.builder()
                .id(processFlowId)
                .href("https://tmf-process-flow/tmf-api/processFlowManagement/v4/processFlow/" + processFlowId)
                .type("processFlow")
                .state("active")
                .baseType("processFlow")
                .channel(List.of())
                .relatedParty(List.of())
                .relatedEntity(List.of(
                        ProcessFlow.RelatedEntity.builder()
                                .id("B54CCE7C0E0840FF86689103A")
                                .href("https://sharp-oneside-task/onesideTaskCatalog/findServiceDiagnosticFromCacheByTransactionId/B54CCE7C0E0840FF86689103A")
                                .name("Internet Service Diagnostic")
                                .role("RelatedEntity")
                                .type("RelatedEntity")
                                .referredType("InternetServiceDiagnostic")
                                .build()
                ))
                .characteristic(List.of(
                        ProcessFlow.Characteristic.builder()
                                .id("B54CCE7C0E0840FF86689103A").name("SDT Transaction ID")
                                .value("").valueType("string").characteristicRelationship(List.of()).build(),
                        ProcessFlow.Characteristic.builder()
                                .id("N/A").name("internetSubscription")
                                .value("").valueType("string").characteristicRelationship(List.of()).build(),
                        ProcessFlow.Characteristic.builder()
                                .id("EZ82449").name("peinNumber")
                                .value("").valueType("string").characteristicRelationship(List.of()).build()
                ))
                .processFlowSpecification(dagKey)
                .build();

        String notificationTopic = pamconsumerProps.notificationTopic();
        notificationKafka.send(MessageBuilder
                .withPayload(processFlow)
                .setHeader(KafkaHeaders.TOPIC, notificationTopic)
                .setHeader(KafkaHeaders.KEY, processFlowId)
                .build());

        log.info("DEMO: published processFlow to {} for processFlowId={}",
                notificationTopic, processFlowId);

        return Map.of(
                "processFlowId", processFlowId,
                "dagKey", dagKey,
                "publishedTo", notificationTopic,
                "message", "processFlow published → " + notificationTopic + " → pamconsumer → orchestrator"
        );
    }

    /**
     * Manually injects a task.signal into the task.command topic.
     *
     * <p>Use this to complete an ASYNC task that is in WAITING status.
     * Check {@code GET /demo/tasks} to find the taskFlowId and downstreamTransactionId
     * of the waiting task, then call this endpoint with those values.</p>
     *
     * @param processFlowId the processFlow UUID (correlationId)
     * @param taskFlowId    the taskFlow UUID from the WAITING event (e.g. tf-31c94387)
     * @param taskFlowHref  the taskFlow href (optional, for TMF-701 reference)
     * @param downstreamTransactionId the downstream transaction ID from awaitingSignal
     * @param downstreamHref the downstream result URL (optional)
     */
    @PostMapping("/signal")
    public Map<String, Object> injectSignal(
            @RequestParam String processFlowId,
            @RequestParam String taskFlowId,
            @RequestParam(defaultValue = "") String taskFlowHref,
            @RequestParam String downstreamTransactionId,
            @RequestParam(defaultValue = "") String downstreamHref) {

        TaskCommand signal = taskCommandFactory.buildBase(
                processFlowId, MessageName.TASK_SIGNAL.getValue(),
                MessageType.SIGNAL, Sources.PAMCONSUMER);

        signal.setTask(TaskCommand.Task.builder()
                .id(taskFlowId).href(taskFlowHref).build());

        signal.setInputs(TaskCommand.Inputs.builder()
                .downstream(TaskCommand.Downstream.builder()
                        .id(downstreamTransactionId).href(downstreamHref).build())
                .externalEventId(UUID.randomUUID().toString())
                .externalType("TaskFinalAsyncResponseSend")
                .reportingSystem("ACUT")
                .build());

        taskCommandPublisher.publish(signal);

        log.info("DEMO: manually injected task.signal for taskFlowId={} downstreamTxnId={}",
                taskFlowId, downstreamTransactionId);

        return Map.of(
                "processFlowId", processFlowId,
                "taskFlowId", taskFlowId,
                "downstreamTransactionId", downstreamTransactionId,
                "signalEventId", signal.getEventId(),
                "message", "task.signal injected → task.command → MockTaskRunner will complete the task"
        );
    }

    @GetMapping("/barriers")
    public List<BatchBarrier> barriers() {
        return barrierRepo.findAll();
    }

    @GetMapping("/tasks")
    public List<TaskExecution> tasks() {
        return taskRepo.findAll();
    }
}
