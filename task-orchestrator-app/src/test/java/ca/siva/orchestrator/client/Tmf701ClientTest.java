package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.FidCredentialsProperties;
import ca.siva.orchestrator.config.HttpClientProperties;
import ca.siva.orchestrator.config.Tmf701Properties;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link Tmf701Client} covering every branch of the
 * <b>GET-then-merge-then-PATCH</b> contract the orchestrator relies on:
 *
 * <ul>
 *   <li>PATCH body Content-Type is {@code application/merge-patch+json}
 *       (RFC 7396), which the TMF-701 server requires.</li>
 *   <li>{@code relatedEntity} append semantics: existing siblings preserved,
 *       duplicates skipped by {@code taskFlowId}, GET failure falls back to
 *       single-entry PATCH instead of stalling the orchestrator.</li>
 *   <li>Validation: null/blank taskFlowId or taskFlowHref produces a no-op
 *       (no HTTP call at all).</li>
 *   <li>Resilience: PATCH failures are swallowed so the orchestration loop
 *       stays alive.</li>
 *   <li>{@link Tmf701Client#patchProcessFlowState} sends ONLY {@code state}
 *       on the wire (thanks to {@code @JsonInclude(NON_NULL)} on
 *       {@link ProcessFlow}).</li>
 * </ul>
 *
 * <p>We bind a {@link MockRestServiceServer} to the same
 * {@link RestClient.Builder} the client is wired with, then drive
 * {@link Tmf701Client#init()} manually (simulating
 * {@code ApplicationReadyEvent}). This exercises the real HTTP request
 * factory path end-to-end without standing up Tomcat.</p>
 */
@ExtendWith(MockitoExtension.class)
class Tmf701ClientTest {

    private static final String PROCESS_FLOW_ID = "pf-42";
    private static final String TASK_FLOW_ID    = "tf-new";
    private static final String TASK_FLOW_HREF  = "http://tmf701/taskFlow/tf-new";
    private static final String ACTION_NAME     = "passwordPushV2";
    private static final String BASE_URL        = "http://tmf701.test/api/v4";
    private static final String PATH_TEMPLATE   = "/processFlow/{id}";
    private static final String EXPECTED_URL    = BASE_URL + "/processFlow/" + PROCESS_FLOW_ID;
    private static final MediaType MERGE_PATCH_JSON =
            MediaType.valueOf("application/merge-patch+json");

    @Mock Environment environment;

    private ObjectMapper          mapper;
    private MockRestServiceServer server;
    private Tmf701Client          client;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        // Bind the mock server to the builder BEFORE the client calls .build()
        // inside init(). Mutations the client performs on the builder
        // (e.g. .baseUrl()) don't detach the mock request factory.
        server = MockRestServiceServer.bindTo(builder).build();

        Tmf701Properties           props = new Tmf701Properties(BASE_URL, PATH_TEMPLATE);
        FidCredentialsProperties   creds = new FidCredentialsProperties("", "");
        when(environment.getProperty("local.server.port")).thenReturn(null);

        // Plain `new RetryTemplate()` uses a default SimpleRetryPolicy that
        // treats every Exception as retryable — that would make every unit
        // test fire multiple wire calls and blow up MockRestServiceServer's
        // ExpectedCount.once() expectations. Install NeverRetryPolicy so each
        // test observes exactly one attempt; retry behaviour itself is covered
        // in a dedicated integration test.
        RetryTemplate noRetryTemplate = new RetryTemplate();
        noRetryTemplate.setRetryPolicy(new NeverRetryPolicy());
        // HttpClientProperties with nulls applies its record defaults
        // (120s timeouts, 408/429/500/502/503/504 retryable set,
        // 3 total attempts / 500ms→4s exponential retry budget) — exactly
        // what the tests want to observe. The RetryTemplate passed above is
        // the NeverRetryPolicy one, so the Retry config is only consulted
        // when the test explicitly wires it; these defaults just keep the
        // bean happy.
        HttpClientProperties httpProps = new HttpClientProperties(null, null, null, null);
        client = new Tmf701Client(props, creds, builder, environment, mapper, noRetryTemplate, httpProps);
        client.init();                                   // simulate ApplicationReadyEvent
    }

    // ─── patchProcessFlowAddTaskFlowRef ───────────────────────────────────

    @Test
    void addTaskFlowRef_preservesExistingRelatedEntity_andAppendsNewOne() throws Exception {
        // GET returns a processFlow with one pre-existing relatedEntity and
        // no taskFlow array yet — the merge should preserve relatedEntity,
        // append the new ref to it, AND seed the taskFlow array with the new
        // TaskFlowRef carrying actionName as @referredType (legacy
        // enrichProcessFlow contract).
        String getResponse = """
                {
                  "id":"pf-42",
                  "relatedEntity":[
                    {"id":"tf-old","href":"http://tmf701/taskFlow/tf-old","role":"TaskFlow","@type":"RelatedEntity"}
                  ]
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(getResponse, MediaType.APPLICATION_JSON));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              // Must be RFC 7396 JSON Merge Patch, not plain application/json.
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes());

                  JsonNode rel  = body.path("relatedEntity");
                  assertThat(rel.isArray()).isTrue();
                  assertThat(rel).hasSize(2);
                  assertThat(rel.get(0).path("id").asText()).isEqualTo("tf-old");
                  assertThat(rel.get(1).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(rel.get(1).path("href").asText()).isEqualTo(TASK_FLOW_HREF);
                  assertThat(rel.get(1).path("role").asText()).isEqualTo("TaskFlow");
                  assertThat(rel.get(1).path("name").asText()).isEqualTo(ACTION_NAME);

                  // NEW — the typed taskFlow[] array must also be populated
                  // with the new TaskFlowRef, mirroring the legacy
                  // processFlow.addTaskFlowItem(buildTaskFlow(...)) call.
                  JsonNode tf = body.path("taskFlow");
                  assertThat(tf.isArray()).isTrue();
                  assertThat(tf).hasSize(1);
                  assertThat(tf.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(tf.get(0).path("href").asText()).isEqualTo(TASK_FLOW_HREF);
                  // @referredType carries the actionName per the legacy
                  // buildTaskFlow(tf.getId(), atReferredType, tf.getHref(), ...)
                  // signature where atReferredType is the per-call action name.
                  assertThat(tf.get(0).path("@referredType").asText()).isEqualTo(ACTION_NAME);

                  // NON_NULL on ProcessFlow: only the two arrays should be
                  // on the wire — state / id are not mutated by the mid-flow
                  // taskFlow append.
                  assertThat(body.has("state")).isFalse();
                  assertThat(body.has("id")).isFalse();
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_preservesExistingTaskFlowArray_andAppendsNewOne() throws Exception {
        // The GET snapshot already has ONE pre-existing taskFlow ref. The
        // merge must preserve it and append the new one — proves the
        // taskFlow[] read-modify-write mirrors the relatedEntity[] one and
        // doesn't accidentally clobber prior tasks under merge-patch
        // (RFC 7396) array-replace semantics.
        String getResponse = """
                {
                  "id":"pf-42",
                  "taskFlow":[
                    {"id":"tf-old","href":"http://tmf701/taskFlow/tf-old","@referredType":"InternetDiagnostic"}
                  ]
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(getResponse, MediaType.APPLICATION_JSON));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes());
                  JsonNode tf = body.path("taskFlow");
                  assertThat(tf.isArray()).isTrue();
                  assertThat(tf).hasSize(2);
                  assertThat(tf.get(0).path("id").asText()).isEqualTo("tf-old");
                  assertThat(tf.get(0).path("@referredType").asText()).isEqualTo("InternetDiagnostic");
                  assertThat(tf.get(1).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(tf.get(1).path("@referredType").asText()).isEqualTo(ACTION_NAME);
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_duplicateTaskFlowId_notAppendedAgain() throws Exception {
        // Both arrays already contain the incoming id — neither should grow
        // on the redelivered call (idempotency on both arrays in lock-step).
        String getResponse = """
                {
                  "id":"pf-42",
                  "relatedEntity":[
                    {"id":"tf-new","href":"http://tmf701/taskFlow/tf-new","role":"TaskFlow","@type":"RelatedEntity"}
                  ],
                  "taskFlow":[
                    {"id":"tf-new","href":"http://tmf701/taskFlow/tf-new","@referredType":"passwordPushV2"}
                  ]
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(getResponse, MediaType.APPLICATION_JSON));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes());
                  JsonNode rel = body.path("relatedEntity");
                  JsonNode tf  = body.path("taskFlow");
                  // Idempotency on BOTH arrays: still exactly one entry each.
                  assertThat(rel).hasSize(1);
                  assertThat(rel.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(tf).hasSize(1);
                  assertThat(tf.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_getFails_stillPatchesWithNewEntryAlone() throws Exception {
        // GET returns 500 — we should not lose this taskFlow ref; fall back
        // to PATCHing with just the new entries on BOTH arrays
        // (previously-attached siblings on either array are sacrificed in
        // this rare failure path).
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes());
                  JsonNode rel = body.path("relatedEntity");
                  JsonNode tf  = body.path("taskFlow");
                  assertThat(rel).hasSize(1);
                  assertThat(rel.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(tf).hasSize(1);
                  assertThat(tf.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
                  assertThat(tf.get(0).path("@referredType").asText()).isEqualTo(ACTION_NAME);
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_blankActionName_taskFlowReferredTypeFallsBackToDefault() throws Exception {
        // actionName blank/null → taskFlow.@referredType falls back to the
        // "TaskFlow" default so the field is never sent empty (TMF-701
        // schema requires it on TaskFlowRef).
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("{\"id\":\"pf-42\"}", MediaType.APPLICATION_JSON));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode tf = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes())
                                       .path("taskFlow");
                  assertThat(tf).hasSize(1);
                  assertThat(tf.get(0).path("@referredType").asText()).isEqualTo("TaskFlow");
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, "  ");
        server.verify();
    }

    @Test
    void addTaskFlowRef_nullTaskFlowId_noHttpCall() {
        // No server.expect(...) — MockRestServiceServer.verify() will fail
        // if any request is made.
        client.patchProcessFlowAddTaskFlowRef(PROCESS_FLOW_ID, null, TASK_FLOW_HREF, ACTION_NAME);
        server.verify();
    }

    @Test
    void addTaskFlowRef_blankTaskFlowHref_noHttpCall() {
        client.patchProcessFlowAddTaskFlowRef(PROCESS_FLOW_ID, TASK_FLOW_ID, "   ", ACTION_NAME);
        server.verify();
    }

    // ─── patchProcessFlowState ────────────────────────────────────────────

    @Test
    void patchState_sendsOnlyStateField_withMergePatchContentType() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes());
                  assertThat(body.path("state").asText()).isEqualTo("completed");
                  // NON_NULL: nothing else on the wire.
                  List<String> fieldNames = new java.util.ArrayList<>();
                  body.fieldNames().forEachRemaining(fieldNames::add);
                  assertThat(fieldNames).containsExactly("state");
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowState(PROCESS_FLOW_ID, ProcessFlowStateType.COMPLETED);

        server.verify();
    }

    @Test
    void patchState_serverError_isSwallowed() {
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Orchestration must not throw on TMF-701 hiccups.
        assertThatCode(() -> client.patchProcessFlowState(PROCESS_FLOW_ID, ProcessFlowStateType.FAILED))
                .doesNotThrowAnyException();

        server.verify();
    }

    // ─── getProcessFlow ───────────────────────────────────────────────────

    @Test
    void getProcessFlow_happyPath_returnsParsedBody() {
        String body = """
                {"id":"pf-42","state":"inProgress"}
                """;
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var result = client.getProcessFlow(PROCESS_FLOW_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("pf-42");
        assertThat(result.get().getState()).isEqualTo("inProgress");
        server.verify();
    }

    @Test
    void getProcessFlow_notFound_returnsEmptyOptional() {
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getProcessFlow(PROCESS_FLOW_ID)).isEmpty();
        server.verify();
    }
}
