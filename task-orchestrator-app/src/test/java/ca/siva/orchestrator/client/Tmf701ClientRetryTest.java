package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.AppConfig;
import ca.siva.orchestrator.config.FidCredentialsProperties;
import ca.siva.orchestrator.config.HttpClientProperties;
import ca.siva.orchestrator.config.Tmf701Properties;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
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
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Dedicated retry-behaviour test for {@link Tmf701Client}.
 *
 * <p>The sibling {@link Tmf701ClientTest} installs a {@code NeverRetryPolicy}
 * so each wire-level assertion sees exactly one call — that suite covers
 * request shape (URL, headers, body) and swallowed-exception plumbing, but it
 * explicitly bypasses the retry logic. This class closes that gap: every test
 * here drives the <b>real</b> {@link RetryTemplate} built by
 * {@link AppConfig#httpCallRetryTemplate(HttpClientProperties)} and proves the
 * budget is honoured end-to-end.</p>
 *
 * <h4>What each test proves</h4>
 * <ul>
 *   <li>{@code retryableStatus_exhaustsBudget} — a persistent 503 triggers
 *       exactly {@code maxAttempts} wire calls (1 initial + 2 retries) and
 *       then re-throws.</li>
 *   <li>{@code retryableStatus_recoversBeforeBudgetExhausted} — 2× 503 then a
 *       200 succeeds on the 3rd attempt; the {@link java.util.Optional} is
 *       non-empty and only 3 wire calls are made.</li>
 *   <li>{@code nonRetryableStatus_triesOnce} — a 404 is NOT retryable, so
 *       exactly 1 wire call is made and the {@code Optional} is empty (the
 *       caller's usual swallow-and-return-empty path).</li>
 *   <li>{@code patchWithRetryableStatus_swallowsAfterBudgetExhausted} — PATCH
 *       attempts 3 wire calls on persistent 503 but still returns normally so
 *       the orchestration loop stays alive.</li>
 * </ul>
 *
 * <h4>Speed</h4>
 * <p>Configured backoff is {@code initial=1ms multiplier=2.0 max=1ms}, so the
 * maximum total inter-attempt wait for 2 retries is ~2 ms. The whole class
 * runs well under 100 ms even on slow laptops.</p>
 */
@ExtendWith(MockitoExtension.class)
class Tmf701ClientRetryTest {

    private static final String PROCESS_FLOW_ID = "pf-retry";
    private static final String BASE_URL        = "http://tmf701.test/api/v4";
    private static final String PATH_TEMPLATE   = "/processFlow/{id}";
    private static final String EXPECTED_URL    = BASE_URL + "/processFlow/" + PROCESS_FLOW_ID;

    @Mock Environment environment;

    private MockRestServiceServer server;
    private Tmf701Client          client;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        Tmf701Properties         props = new Tmf701Properties(BASE_URL, PATH_TEMPLATE);
        FidCredentialsProperties creds = new FidCredentialsProperties("", "");
        when(environment.getProperty("local.server.port")).thenReturn(null);

        // Real retry budget: 3 total attempts, near-zero backoff so the test
        // runs fast. Retryable-statuses list is the record default
        // (408, 429, 500, 502, 503, 504).
        HttpClientProperties.Retry retry = new HttpClientProperties.Retry(
                3,                          // 1 initial + 2 retries
                Duration.ofMillis(1),       // initial backoff
                Duration.ofMillis(1),       // max backoff (cap)
                2.0);                       // multiplier
        HttpClientProperties httpProps = new HttpClientProperties(
                Duration.ofSeconds(1),      // connect timeout (unused by mock)
                Duration.ofSeconds(1),      // read timeout   (unused by mock)
                List.of(408, 429, 500, 502, 503, 504),
                retry);

        // The RetryTemplate under test is the REAL one the production bean
        // container would produce. This is the whole point of this class.
        RetryTemplate retryTemplate = new AppConfig().httpCallRetryTemplate(httpProps);

        client = new Tmf701Client(props, creds, builder, environment, mapper, retryTemplate, httpProps);
        client.init();                                   // simulate ApplicationReadyEvent
    }

    // ─── retryable 5xx: budget exhausted ──────────────────────────────────

    /**
     * Persistent 503 should produce exactly 3 wire calls (1 initial + 2
     * retries) then the client's swallow-path returns {@code Optional.empty()}.
     * If the budget is off-by-one in either direction this test will fail
     * because {@code MockRestServiceServer.verify()} counts exact matches.
     */
    @Test
    void retryableStatus_exhaustsBudget() {
        // 3 sequential expectations, each fulfilled by ONE wire call. If the
        // retry budget is wrong (e.g. 2 attempts or 4 attempts) the second
        // verify() below will fail with "unexpected call" or
        // "expected: 3 but was: N".
        for (int i = 0; i < 3; i++) {
            server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
                  .andExpect(method(HttpMethod.GET))
                  .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        var result = client.getProcessFlow(PROCESS_FLOW_ID);

        assertThat(result).isEmpty();
        server.verify();                                 // all 3 expectations consumed
    }

    // ─── retryable 5xx: recovers before budget exhausted ──────────────────

    /**
     * Two 503s followed by a 200 on the 3rd attempt should succeed. Proves
     * the retry loop re-invokes the supplier and that a partial failure does
     * not fall off the hot path.
     */
    @Test
    void retryableStatus_recoversBeforeBudgetExhausted() {
        String body = """
                { "id": "pf-retry", "state": "inProgress" }
                """;

        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var result = client.getProcessFlow(PROCESS_FLOW_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("pf-retry");
        server.verify();
    }

    // ─── non-retryable 4xx: single attempt ────────────────────────────────

    /**
     * 404 is NOT in the retryable-status list. The client must NOT retry;
     * exactly one wire call is expected. If the retryable-status list were
     * misconfigured (e.g. included 404) the second expectation setup below
     * would be needed and this test would fail — which is the exact signal
     * we want.
     */
    @Test
    void nonRetryableStatus_triesOnce() {
        server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        var result = client.getProcessFlow(PROCESS_FLOW_ID);

        assertThat(result).isEmpty();
        server.verify();                                 // exactly ONE call was made
    }

    // ─── patch: retries exhaust, then swallowed ───────────────────────────

    /**
     * PATCH on a retryable status: budget is exhausted after 3 wire calls,
     * but {@code doPatch} still catches the final exception so the
     * orchestration loop keeps running. Proves that retries happen BEFORE
     * the swallow, not instead of it.
     */
    @Test
    void patchWithRetryableStatus_swallowsAfterBudgetExhausted() {
        for (int i = 0; i < 3; i++) {
            server.expect(ExpectedCount.once(), requestTo(EXPECTED_URL))
                  .andExpect(method(HttpMethod.PATCH))
                  .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThatCode(() -> client.patchProcessFlowState(PROCESS_FLOW_ID, ProcessFlowStateType.COMPLETED))
                .doesNotThrowAnyException();
        server.verify();                                 // all 3 expectations consumed
    }
}
