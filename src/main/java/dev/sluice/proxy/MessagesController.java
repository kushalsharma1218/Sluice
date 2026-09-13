package dev.sluice.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sluice.auth.Principal;
import dev.sluice.auth.PrincipalResolver;
import dev.sluice.budget.PreflightDecision;
import dev.sluice.budget.SpendGate;
import dev.sluice.config.SluiceProperties;
import dev.sluice.ledger.HoldTicket;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import dev.sluice.pricing.CostCalculator;
import dev.sluice.pricing.TokenEstimator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The Anthropic-compatible surface. Pointing an SDK at Sluice is a {@code base_url}
 * change and nothing more: the request and response bodies are relayed unmodified.
 *
 * <p>Sluice never stores a prompt or a completion. It reads {@code model},
 * {@code max_tokens} and {@code stream} out of the request, counts tokens out of
 * the response, and forgets the rest.
 */
@RestController
public class MessagesController {

    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
    private static final String REQUEST_ID_HEADER = "x-sluice-request-id";
    private static final String OVERHEAD_HEADER = "x-sluice-overhead-ms";
    private static final int RELAY_BUFFER_BYTES = 8 * 1024;

    private final PrincipalResolver principals;
    private final SpendGate gate;
    private final MeteringService metering;
    private final AnthropicClient provider;
    private final CostCalculator costs;
    private final TokenEstimator estimator;
    private final Settler settler;
    private final ObjectMapper mapper;
    private final SluiceProperties properties;

    public MessagesController(PrincipalResolver principals, SpendGate gate, MeteringService metering,
                              AnthropicClient provider, CostCalculator costs, TokenEstimator estimator,
                              Settler settler, ObjectMapper mapper, SluiceProperties properties) {
        this.principals = principals;
        this.gate = gate;
        this.metering = metering;
        this.provider = provider;
        this.costs = costs;
        this.estimator = estimator;
        this.settler = settler;
        this.mapper = mapper;
        this.properties = properties;
    }

    @PostMapping(path = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> messages(@RequestBody byte[] rawBody,
                                                          HttpServletRequest request) {
        long startedAtNanos = System.nanoTime();
        Principal principal = principals.resolve(request);

        JsonNode body = parse(rawBody);
        RequestSpec spec = RequestSpec.parse(body, properties.hold().defaultMaxTokens());
        String requestId = resolveRequestId(request);

        if (metering.findHold(requestId).isPresent()) {
            throw new DuplicateRequestException(
                    ("request id '%s' has already been processed. Sluice does not store response "
                            + "bodies, so it cannot replay the original answer; send a new request id "
                            + "to make a new call.").formatted(requestId));
        }

        // Worst case: every input token uncached, plus a full max_tokens of output.
        long estimatedInputTokens = estimator.estimateInputTokens(body);
        long estimateMicros = costs.estimateMicros(
                spec.model(), estimatedInputTokens, spec.maxTokens(), Instant.now());

        PreflightDecision preflight = gate.preflight(principal, estimateMicros);
        if (!preflight.allowed()) {
            log.debug("preflight says no for account {} ({} in {}ms); confirming against Postgres",
                    principal.accountId(), preflight.source(), preflight.elapsedMillis());
        }

        // Authoritative. Throws BudgetExceededException -> 402, before any provider call.
        HoldTicket ticket = gate.reserve(principal, requestId, estimateMicros,
                properties.hold().ttl(),
                Map.of("model", spec.model(),
                        "estimated_input_tokens", estimatedInputTokens,
                        "max_tokens", spec.maxTokens(),
                        "stream", spec.stream()));

        // Everything Sluice does before the provider is involved: auth, parse,
        // estimate, budget check, hold. Reported back on every response so the
        // gateway's own cost is observable from the client side.
        long gatewayOverheadNanos = System.nanoTime() - startedAtNanos;

        HttpResponse<InputStream> upstream;
        try {
            upstream = provider.forward("/v1/messages", rawBody, request);
        } catch (RuntimeException e) {
            settler.release(requestId, "provider call failed: " + e.getMessage());
            throw e;
        }

        if (upstream.statusCode() >= 400) {
            return relayProviderError(upstream, requestId, gatewayOverheadNanos);
        }
        return spec.stream()
                ? relayStream(upstream, requestId, spec, ticket, startedAtNanos, gatewayOverheadNanos)
                : relayComplete(upstream, requestId, spec, ticket, startedAtNanos, gatewayOverheadNanos);
    }

    /**
     * Non-streaming. The body is small and complete, so it is read, counted, and
     * relayed -- which lets the cost ride back on response headers.
     */
    private ResponseEntity<StreamingResponseBody> relayComplete(
            HttpResponse<InputStream> upstream, String requestId, RequestSpec spec,
            HoldTicket ticket, long startedAtNanos, long gatewayOverheadNanos) {

        byte[] payload;
        try (InputStream in = upstream.body()) {
            payload = in.readAllBytes();
        } catch (IOException e) {
            settler.release(requestId, "failed reading provider response: " + e.getMessage());
            throw new ProviderException("failed reading provider response", e);
        }

        UsageTap tap = new UsageTap(mapper);
        try {
            tap.accept(mapper.readTree(payload));
        } catch (IOException e) {
            log.warn("provider returned a body we could not parse for request {}", requestId);
        }
        settler.settle(requestId, spec.model(), tap, false, startedAtNanos);

        HttpHeaders headers = relayHeaders(upstream, requestId, gatewayOverheadNanos);
        metering.findHold(requestId).ifPresent(h -> {
            var usage = tap.tokenUsage();
            headers.add("x-sluice-input-tokens", Long.toString(usage.inputTokens()));
            headers.add("x-sluice-output-tokens", Long.toString(usage.outputTokens()));
            headers.add("x-sluice-cost", Micros.format(
                    costs.costMicros(tap.model() == null ? spec.model() : tap.model(),
                            usage, Instant.now())));
            headers.add("x-sluice-currency", ticket.currency());
        });

        return ResponseEntity.status(upstream.statusCode())
                .headers(headers)
                .body(out -> {
                    out.write(payload);
                    out.flush();
                });
    }

    /**
     * Streaming. Bytes go out as they arrive -- nothing is buffered, so
     * time-to-first-token is a copy loop plus the tap.
     *
     * <p>If the client disconnects, the write throws, and the call is settled on
     * whatever was actually delivered rather than leaving the hold to rot.
     */
    private ResponseEntity<StreamingResponseBody> relayStream(
            HttpResponse<InputStream> upstream, String requestId, RequestSpec spec,
            HoldTicket ticket, long startedAtNanos, long gatewayOverheadNanos) {

        HttpHeaders headers = relayHeaders(upstream, requestId, gatewayOverheadNanos);

        StreamingResponseBody body = out -> {
            UsageTap tap = new UsageTap(mapper);
            boolean clientGone = false;
            try (InputStream in = upstream.body()) {
                byte[] buffer = new byte[RELAY_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    tap.feed(buffer, 0, read);
                    try {
                        out.write(buffer, 0, read);
                        out.flush();
                    } catch (IOException e) {
                        // The client hung up. Stop reading, which closes the upstream
                        // connection and tells the provider to stop generating. Draining
                        // it instead would keep the meter running on tokens nobody will
                        // ever see, and bill the customer for them.
                        clientGone = true;
                        log.debug("client disconnected mid-stream on {}, cancelling upstream",
                                requestId);
                        break;
                    }
                }
            } catch (IOException e) {
                log.debug("upstream stream ended early on {}: {}", requestId, e.toString());
            } finally {
                tap.finish();
                // Settles on whatever actually reached the client. No authoritative
                // usage block arrived, so this is recorded as a partial charge.
                settler.settle(requestId, spec.model(), tap, true, startedAtNanos);
            }
            if (clientGone) {
                throw new IOException("client disconnected during stream " + requestId);
            }
        };

        return ResponseEntity.status(upstream.statusCode()).headers(headers).body(body);
    }

    /**
     * The provider refused. Nothing was consumed, so the hold is released in full
     * and the error is relayed verbatim -- the client sees the provider's own
     * message, not a Sluice paraphrase of it.
     */
    private ResponseEntity<StreamingResponseBody> relayProviderError(
            HttpResponse<InputStream> upstream, String requestId, long gatewayOverheadNanos) {

        byte[] payload;
        try (InputStream in = upstream.body()) {
            payload = in.readAllBytes();
        } catch (IOException e) {
            payload = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}".getBytes(StandardCharsets.UTF_8);
        }
        settler.release(requestId, "provider returned " + upstream.statusCode());

        return ResponseEntity.status(upstream.statusCode())
                .headers(relayHeaders(upstream, requestId, gatewayOverheadNanos))
                .body(new ByteArrayBody(payload));
    }

    private HttpHeaders relayHeaders(HttpResponse<InputStream> upstream, String requestId,
                                     long gatewayOverheadNanos) {
        HttpHeaders headers = new HttpHeaders();
        upstream.headers().map().forEach((name, values) -> {
            if (AnthropicClient.isForwardableResponseHeader(name)) {
                values.forEach(value -> headers.add(name, value));
            }
        });
        headers.set(REQUEST_ID_HEADER, requestId);
        headers.set(OVERHEAD_HEADER, String.format("%.3f", gatewayOverheadNanos / 1_000_000.0));
        return headers;
    }

    private JsonNode parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new InvalidRequestException("request body is empty");
        }
        try {
            JsonNode node = mapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw new InvalidRequestException("request body must be a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new InvalidRequestException("request body is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * The idempotency key for the whole hold/settle cycle. A client that supplies
     * its own gets exactly-once billing across its retries; one that does not gets
     * a fresh id per call.
     */
    private String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied == null || supplied.isBlank()) {
            supplied = request.getHeader("idempotency-key");
        }
        if (supplied == null || supplied.isBlank()) {
            return "req_" + UUID.randomUUID();
        }
        if (supplied.length() > 200) {
            throw new InvalidRequestException(REQUEST_ID_HEADER + " must be at most 200 characters");
        }
        return supplied.trim();
    }

    private record ByteArrayBody(byte[] payload) implements StreamingResponseBody {
        @Override
        public void writeTo(OutputStream out) throws IOException {
            out.write(payload);
            out.flush();
        }
    }
}
