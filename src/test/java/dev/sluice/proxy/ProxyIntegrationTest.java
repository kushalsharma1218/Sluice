package dev.sluice.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sluice.account.Account;
import dev.sluice.auth.IssuedKey;
import dev.sluice.ledger.Hold;
import dev.sluice.ledger.HoldStatus;
import dev.sluice.ledger.LedgerService;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import dev.sluice.support.AbstractWebTest;
import dev.sluice.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through the real HTTP stack against a stand-in provider.
 *
 * <p>Covers M0 (the proxy shape), M2 (hold, settle, exactly-once) and M3
 * (streaming, partial settlement, client disconnect).
 */
class ProxyIntegrationTest extends AbstractWebTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    MeteringService metering;
    @Autowired
    ObjectMapper mapper;

    private Account team;
    private String virtualKey;

    @BeforeEach
    void seedAccount() {
        Account org = fixtures.org("acme");
        team = fixtures.team(org, "platform");
        fixtures.fund(team, "5.00");
        IssuedKey issued = fixtures.key(team);
        virtualKey = issued.secret();
    }

    // ------------------------------------------------------------------ M0/M2

    @Test
    @DisplayName("a non-streaming call is relayed verbatim and charged from the provider's own usage block")
    void nonStreamingCallIsProxiedAndMetered() throws Exception {
        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);

        HttpResponse<String> response = post(messageBody(1000, false), "req-basic");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("content").get(0).path("text").asText())
                .as("the provider's body reaches the client unmodified")
                .isEqualTo("hello");

        // 1000 input @ $5/MTok = $0.005; 500 output @ $25/MTok = $0.0125.
        assertThat(usageMicros()).isEqualTo(micros("0.0175"));
        assertThat(available()).isEqualTo(micros("4.9825"));
        assertThat(held()).as("nothing is left reserved").isZero();
        assertLedgerIsSound();

        assertThat(response.headers().firstValue("x-sluice-cost")).contains("0.017500");
        assertThat(response.headers().firstValue("x-sluice-input-tokens")).contains("1000");
    }

    @Test
    @DisplayName("the virtual key is swapped for the provider key and never forwarded")
    void virtualKeyIsNeverForwarded() throws Exception {
        ANTHROPIC.respondWithMessage("claude-opus-5", 100, 50);
        post(messageBody(1000, false), "req-headers");

        var forwarded = ANTHROPIC.lastRequest();
        assertThat(forwarded.header("x-api-key")).isEqualTo("sk-ant-provider-key");
        assertThat(forwarded.header("x-api-key")).isNotEqualTo(virtualKey);
        assertThat(forwarded.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(forwarded.body()).contains("\"model\":\"claude-opus-5\"");
    }

    @Test
    @DisplayName("an unknown virtual key is refused and no provider call is made")
    void unknownKeyIsRefused() throws Exception {
        ANTHROPIC.respondWithMessage("claude-opus-5", 100, 50);
        int before = ANTHROPIC.requests().size();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", "sk-sluice-nonsense")
                        .POST(HttpRequest.BodyPublishers.ofString(messageBody(1000, false)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(ANTHROPIC.requests()).hasSize(before);
    }

    @Test
    @DisplayName("replaying a request id is refused rather than charged twice")
    void replayingARequestIdChargesOnce() throws Exception {
        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);

        assertThat(post(messageBody(1000, false), "req-once").statusCode()).isEqualTo(200);
        int providerCalls = ANTHROPIC.requests().size();

        HttpResponse<String> replay = post(messageBody(1000, false), "req-once");

        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(ANTHROPIC.requests())
                .as("the replay never reaches the provider")
                .hasSize(providerCalls);
        assertThat(usageMicros()).as("charged exactly once").isEqualTo(micros("0.0175"));
        assertThat(countUsageRecords()).isEqualTo(1);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("a provider error releases the hold in full and charges nothing")
    void providerErrorReleasesTheHold() throws Exception {
        ANTHROPIC.respondWithError(529, "overloaded_error", "upstream is busy");

        HttpResponse<String> response = post(messageBody(1000, false), "req-error");

        assertThat(response.statusCode()).isEqualTo(529);
        assertThat(response.body()).contains("overloaded_error");
        assertThat(available()).as("the reservation comes back").isEqualTo(micros("5.00"));
        assertThat(usageMicros()).isZero();
        assertThat(held()).isZero();
        assertThat(metering.findHold("req-error")).get()
                .extracting(Hold::status).isEqualTo(HoldStatus.RELEASED);
        assertLedgerIsSound();
    }

    // --------------------------------------------------------------------- M3

    @Test
    @DisplayName("a streamed call relays every event and settles on the provider's final usage")
    void streamingCallIsRelayedAndSettled() throws Exception {
        ANTHROPIC.respondWithStream("claude-opus-5", 2000,
                List.of("Hello", " there", " world"), 750, true, 0);

        HttpResponse<String> response = post(messageBody(1000, true), "req-stream");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("event: message_start")
                .contains("event: content_block_delta")
                .contains("event: message_delta")
                .contains("event: message_stop");

        // 2000 input @ $5/MTok = $0.010; 750 output @ $25/MTok = $0.01875.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(usageMicros()).isEqualTo(micros("0.02875")));
        assertThat(held()).isZero();

        var record = usageRow("req-stream");
        assertThat(record.streamed()).isTrue();
        assertThat(record.partial()).as("the final usage block arrived").isFalse();
        assertThat(record.outputTokens()).isEqualTo(750);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("killing the client mid-stream leaves no orphaned hold and charges only delivered tokens")
    void clientDisconnectSettlesPartially() throws Exception {
        // Twenty slow chunks; the client will be killed after the first few.
        ANTHROPIC.respondWithStream("claude-opus-5", 2000,
                List.of("aaaaaaaa".repeat(4), "bbbbbbbb".repeat(4), "cccccccc".repeat(4),
                        "dddddddd".repeat(4), "eeeeeeee".repeat(4), "ffffffff".repeat(4),
                        "gggggggg".repeat(4), "hhhhhhhh".repeat(4), "iiiiiiii".repeat(4),
                        "jjjjjjjj".repeat(4)),
                100_000, true, 120);

        String requestId = "req-killed";
        String body = messageBody(1000, true);
        String request = ("POST /v1/messages HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "content-type: application/json\r\n"
                + "x-api-key: " + virtualKey + "\r\n"
                + "x-sluice-request-id: " + requestId + "\r\n"
                + "content-length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "connection: close\r\n\r\n" + body);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read just enough to know the stream has started, then pull the plug.
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[512];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int seen = 0;
            while (seen < 400 && System.nanoTime() < deadline) {
                int read = in.read(buffer);
                if (read == -1) {
                    break;
                }
                seen += read;
            }
            assertThat(seen).as("the stream started before we killed it").isPositive();
            socket.setSoLinger(true, 0); // RST, not a graceful close
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var hold = metering.findHold(requestId);
            assertThat(hold).isPresent();
            assertThat(hold.get().status())
                    .as("no orphaned hold is left behind")
                    .isNotEqualTo(HoldStatus.OPEN);
        });

        assertThat(held()).as("nothing stays reserved").isZero();

        var record = usageRow(requestId);
        assertThat(record.partial())
                .as("the provider never reported a final count, so this is a partial charge")
                .isTrue();
        assertThat(record.outputTokens())
                .as("charged for what was delivered, not for the 100,000 tokens never sent")
                .isGreaterThan(0)
                .isLessThan(1000);
        assertThat(usageMicros()).isPositive().isLessThan(micros("0.05"));
        assertLedgerIsSound();
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> post(String body, String requestId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", virtualKey)
                        .header("x-sluice-request-id", requestId)
                        .header("anthropic-version", "2023-06-01")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String messageBody(int maxTokens, boolean stream) {
        return """
                {"model":"claude-opus-5","max_tokens":%d,"stream":%s,
                 "messages":[{"role":"user","content":"Say hello."}]}"""
                .formatted(maxTokens, stream);
    }

    private long available() {
        return ledger.availableMicros(team.id(), "USD");
    }

    private long held() {
        return ledger.heldMicros(team.id(), "USD");
    }

    private long usageMicros() {
        return ledger.usageMicros(team.id(), "USD");
    }

    private int countUsageRecords() {
        Integer count = jdbc.queryForObject("select count(*) from usage_record", Integer.class);
        return count == null ? 0 : count;
    }

    private UsageRow usageRow(String requestId) {
        return jdbc.queryForObject("""
                select input_tokens, output_tokens, cost_micros, streamed, partial
                  from usage_record where request_id = ?
                """,
                (rs, rowNum) -> new UsageRow(rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("cost_micros"), rs.getBoolean("streamed"), rs.getBoolean("partial")),
                requestId);
    }

    private static long micros(String decimal) {
        return Micros.fromDecimal(new BigDecimal(decimal));
    }

    private record UsageRow(long inputTokens, long outputTokens, long costMicros,
                            boolean streamed, boolean partial) {
    }
}
