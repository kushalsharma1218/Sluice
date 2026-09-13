package dev.sluice.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sluice.support.AbstractWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin API is the whole operator interface in v1 -- there is no web UI --
 * so the seed-to-first-metered-call path has to work from HTTP alone.
 */
class AdminApiTest extends AbstractWebTest {

    private static final String TOKEN = "test-admin-token";

    @Autowired
    ObjectMapper mapper;

    @Test
    @DisplayName("an operator can go from nothing to a metered call with admin calls alone")
    void fullOperatorJourney() throws Exception {
        JsonNode org = post("/admin/accounts", """
                {"name":"acme","type":"ORG","currency":"USD"}""");
        String orgId = org.path("id").asText();
        assertThat(org.path("currency").asText()).isEqualTo("USD");

        JsonNode team = post("/admin/accounts", """
                {"name":"platform","type":"TEAM","parentId":"%s"}""".formatted(orgId));
        String teamId = team.path("id").asText();
        assertThat(team.path("currency").asText())
                .as("a child inherits its parent's currency")
                .isEqualTo("USD");

        post("/admin/accounts/" + teamId + "/credits", """
                {"amount":"25.00","reference":"invoice-1","note":"first top-up"}""");

        JsonNode budget = put("/admin/accounts/" + teamId + "/budget", """
                {"period":"MONTHLY","limit":"10.00","hardStop":true}""");
        assertThat(budget.path("limit").asText()).isEqualTo("10.000000");
        assertThat(budget.path("remaining").asText()).isEqualTo("10.000000");

        JsonNode issued = post("/admin/accounts/" + teamId + "/keys", """
                {"name":"ci"}""");
        String key = issued.path("key").asText();
        assertThat(key).startsWith("sk-sluice-");

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        HttpResponse<String> call = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", key)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"claude-opus-5","max_tokens":256,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(call.statusCode()).isEqualTo(200);

        JsonNode balance = get("/admin/accounts/" + teamId + "/balance");
        assertThat(balance.path("available").asText()).isEqualTo("24.982500");
        assertThat(balance.path("lifetimeUsage").asText()).isEqualTo("0.017500");
        assertThat(balance.path("held").asText()).isEqualTo("0.000000");

        JsonNode usage = get("/admin/accounts/" + teamId + "/usage");
        assertThat(usage.path("totalCost").asText()).isEqualTo("0.017500");
        assertThat(usage.path("byModel").get(0).path("model").asText()).isEqualTo("claude-opus-5");
        assertThat(usage.path("records")).hasSize(1);

        JsonNode orgUsage = get("/admin/accounts/" + orgId + "/usage");
        assertThat(orgUsage.path("totalCost").asText())
                .as("usage rolls up to the parent")
                .isEqualTo("0.017500");

        JsonNode ledger = get("/admin/accounts/" + teamId + "/ledger");
        assertThat(ledger).isNotEmpty();
        assertThat(ledger.findValuesAsText("type")).contains("DEPOSIT", "HOLD", "SETTLE");

        JsonNode integrity = get("/admin/ledger/integrity");
        assertThat(integrity.path("healthy").asBoolean()).isTrue();
        assertThat(integrity.path("globalImbalanceMicros").asLong()).isZero();
    }

    @Test
    @DisplayName("crediting twice with the same reference deposits once")
    void creditsAreIdempotentOnReference() throws Exception {
        String teamId = post("/admin/accounts", """
                {"name":"solo","type":"ORG","currency":"USD"}""").path("id").asText();

        JsonNode first = post("/admin/accounts/" + teamId + "/credits", """
                {"amount":"10.00","reference":"wire-123"}""");
        JsonNode second = post("/admin/accounts/" + teamId + "/credits", """
                {"amount":"10.00","reference":"wire-123"}""");

        assertThat(first.path("created").asBoolean()).isTrue();
        assertThat(second.path("created").asBoolean())
                .as("a replayed deposit is a no-op, not free money")
                .isFalse();
        assertThat(get("/admin/accounts/" + teamId + "/balance").path("available").asText())
                .isEqualTo("10.000000");
    }

    @Test
    @DisplayName("a revoked key stops working immediately")
    void revokedKeysAreRefused() throws Exception {
        String orgId = post("/admin/accounts", """
                {"name":"acme","type":"ORG","currency":"USD"}""").path("id").asText();
        post("/admin/accounts/" + orgId + "/credits", """
                {"amount":"10.00","reference":"seed"}""");
        JsonNode issued = post("/admin/accounts/" + orgId + "/keys", """
                {"name":"leaked"}""");

        send("DELETE", "/admin/keys/" + issued.path("id").asText(), null);

        ANTHROPIC.respondWithMessage("claude-opus-5", 10, 10);
        HttpResponse<String> call = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", issued.path("key").asText())
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"claude-opus-5","max_tokens":16,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(call.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the admin API refuses a request without the token")
    void adminRequiresItsToken() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/admin/accounts"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"sneaky","type":"ORG"}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("usage exports as CSV for whoever reconciles the invoice")
    void usageExportsAsCsv() throws Exception {
        String orgId = post("/admin/accounts", """
                {"name":"acme","type":"ORG","currency":"USD"}""").path("id").asText();
        post("/admin/accounts/" + orgId + "/credits", """
                {"amount":"10.00","reference":"seed"}""");
        String key = post("/admin/accounts/" + orgId + "/keys", """
                {"name":"ci"}""").path("key").asText();

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"claude-opus-5","max_tokens":256,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> csv = send("GET", "/admin/accounts/" + orgId + "/usage.csv", null);

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body().lines().findFirst().orElseThrow())
                .isEqualTo("timestamp,account_id,request_id,model,input_tokens,output_tokens,"
                        + "cache_creation_input_tokens,cache_read_input_tokens,cost,currency,"
                        + "streamed,partial,latency_ms");
        assertThat(csv.body()).contains("claude-opus-5,1000,500,0,0,0.017500,USD");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = send("POST", path, body);
        assertThat(response.statusCode())
                .as("POST %s -> %s", path, response.body())
                .isIn(200, 201);
        return mapper.readTree(response.body());
    }

    private JsonNode put(String path, String body) throws Exception {
        HttpResponse<String> response = send("PUT", path, body);
        assertThat(response.statusCode()).as("PUT %s -> %s", path, response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + TOKEN)
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
