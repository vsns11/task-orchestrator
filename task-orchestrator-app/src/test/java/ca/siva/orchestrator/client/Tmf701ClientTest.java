package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.FidCredentialsProperties;
import ca.siva.orchestrator.config.Tmf701Properties;
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

        client = new Tmf701Client(props, creds, builder, environment, mapper);
        client.init();                                   // simulate ApplicationReadyEvent
    }

    // ─── patchProcessFlowAddTaskFlowRef ───────────────────────────────────

    @Test
    void addTaskFlowRef_preservesExistingRelatedEntity_andAppendsNewOne() throws Exception {
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
                  // NON_NULL on ProcessFlow: only relatedEntity should be on the wire.
                  assertThat(body.has("state")).isFalse();
                  assertThat(body.has("id")).isFalse();
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_duplicateTaskFlowId_notAppendedAgain() throws Exception {
        String getResponse = """
                {
                  "id":"pf-42",
                  "relatedEntity":[
                    {"id":"tf-new","href":"http://tmf701/taskFlow/tf-new","role":"TaskFlow","@type":"RelatedEntity"}
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
                  JsonNode rel = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes())
                                        .path("relatedEntity");
                  // Idempotency: still exactly one entry, not two.
                  assertThat(rel).hasSize(1);
                  assertThat(rel.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

        server.verify();
    }

    @Test
    void addTaskFlowRef_getFails_stillPatchesWithNewEntryAlone() throws Exception {
        // GET returns 500 — we should not lose this taskFlow ref; fall back
        // to PATCHing with just the new entry (previously-attached siblings
        // are sacrificed in this rare failure path).
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andExpect(content().contentType(MERGE_PATCH_JSON))
              .andExpect(request -> {
                  JsonNode rel = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes())
                                        .path("relatedEntity");
                  assertThat(rel).hasSize(1);
                  assertThat(rel.get(0).path("id").asText()).isEqualTo(TASK_FLOW_ID);
              })
              .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.patchProcessFlowAddTaskFlowRef(
                PROCESS_FLOW_ID, TASK_FLOW_ID, TASK_FLOW_HREF, ACTION_NAME);

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

        client.patchProcessFlowState(PROCESS_FLOW_ID, "completed");

        server.verify();
    }

    @Test
    void patchState_serverError_isSwallowed() {
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.PATCH))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Orchestration must not throw on TMF-701 hiccups.
        assertThatCode(() -> client.patchProcessFlowState(PROCESS_FLOW_ID, "failed"))
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
