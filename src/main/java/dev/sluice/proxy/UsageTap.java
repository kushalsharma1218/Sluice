package dev.sluice.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sluice.pricing.TokenUsage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extracts token usage from a response without altering a byte of it.
 *
 * <p>For streaming responses, bytes are relayed as they arrive and fed here in
 * parallel; this class never buffers the body, only the current line. Two numbers
 * matter:
 *
 * <ul>
 *   <li>{@code message_start} carries the authoritative input token count,
 *       including the cache read/write split.</li>
 *   <li>{@code message_delta} carries the cumulative output token count. It
 *       arrives last, and it is the provider's own number -- which is why Sluice
 *       trusts it over anything it could count itself.</li>
 * </ul>
 *
 * <p>If the stream ends before {@code message_delta} (the client hung up, the
 * connection dropped), there is no authoritative output count, so
 * {@link #tokenUsage()} falls back to an estimate from the text actually
 * delivered and {@link #hasAuthoritativeOutput()} reports false. That call is
 * settled as partial: the customer pays for what reached them.
 */
public class UsageTap {

    /** Same heuristic as the pre-flight estimator, used only for partial streams. */
    private static final double CHARS_PER_TOKEN = 4.0;

    private final ObjectMapper mapper;
    private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream(512);

    private String model;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreationInputTokens;
    private long cacheReadInputTokens;
    private long deliveredCharacters;
    private boolean sawMessageStart;
    private boolean authoritativeOutput;
    private String stopReason;
    private String providerErrorType;

    public UsageTap(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Feed relayed bytes. Safe to call with arbitrary chunk boundaries. */
    public void feed(byte[] buffer, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = buffer[i];
            if (b == '\n') {
                consumeLine(currentLine.toString(StandardCharsets.UTF_8));
                currentLine.reset();
            } else {
                currentLine.write(b);
            }
        }
    }

    /** Call once the stream is finished to process any unterminated trailing line. */
    public void finish() {
        if (currentLine.size() > 0) {
            consumeLine(currentLine.toString(StandardCharsets.UTF_8));
            currentLine.reset();
        }
    }

    private void consumeLine(String rawLine) {
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        try {
            accept(mapper.readTree(payload));
        } catch (Exception e) {
            // A line we cannot parse is not a reason to fail the customer's call.
            // The settle path falls back to the estimate and marks the record partial.
        }
    }

    /** Also used for non-streaming responses, where the whole body is one object. */
    public void accept(JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "message_start" -> {
                JsonNode message = node.path("message");
                sawMessageStart = true;
                readModel(message);
                readUsage(message.path("usage"), false);
            }
            case "content_block_delta" -> {
                JsonNode delta = node.path("delta");
                deliveredCharacters += delta.path("text").asText("").length();
                deliveredCharacters += delta.path("partial_json").asText("").length();
                deliveredCharacters += delta.path("thinking").asText("").length();
            }
            case "message_delta" -> {
                stopReason = node.path("delta").path("stop_reason").asText(null);
                readUsage(node.path("usage"), true);
            }
            case "error" -> providerErrorType = node.path("error").path("type").asText("unknown");
            // A complete non-streaming response body.
            case "message" -> {
                readModel(node);
                stopReason = node.path("stop_reason").asText(null);
                readUsage(node.path("usage"), true);
            }
            default -> {
                // message_stop, content_block_start/stop, ping: nothing to count.
            }
        }
    }

    private void readModel(JsonNode node) {
        String value = node.path("model").asText(null);
        if (value != null && !value.isBlank()) {
            model = value;
        }
    }

    private void readUsage(JsonNode usage, boolean authoritative) {
        if (usage == null || usage.isMissingNode() || !usage.isObject()) {
            return;
        }
        if (usage.has("input_tokens")) {
            inputTokens = usage.path("input_tokens").asLong(inputTokens);
        }
        if (usage.has("cache_creation_input_tokens")) {
            cacheCreationInputTokens = usage.path("cache_creation_input_tokens").asLong(0);
        }
        if (usage.has("cache_read_input_tokens")) {
            cacheReadInputTokens = usage.path("cache_read_input_tokens").asLong(0);
        }
        if (usage.has("output_tokens")) {
            outputTokens = usage.path("output_tokens").asLong(outputTokens);
            if (authoritative) {
                authoritativeOutput = true;
            }
        }
    }

    public TokenUsage tokenUsage() {
        long output = authoritativeOutput
                ? outputTokens
                : Math.max(outputTokens, (long) Math.ceil(deliveredCharacters / CHARS_PER_TOKEN));
        return new TokenUsage(inputTokens, output, cacheCreationInputTokens, cacheReadInputTokens);
    }

    /** False when the stream ended before the provider reported its final count. */
    public boolean hasAuthoritativeOutput() {
        return authoritativeOutput;
    }

    public boolean sawAnyUsage() {
        return sawMessageStart || authoritativeOutput || inputTokens > 0;
    }

    public String model() {
        return model;
    }

    public String stopReason() {
        return stopReason;
    }

    public String providerErrorType() {
        return providerErrorType;
    }

    public long deliveredCharacters() {
        return deliveredCharacters;
    }
}
