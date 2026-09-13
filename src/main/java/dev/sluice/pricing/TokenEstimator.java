package dev.sluice.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * A pre-flight guess at input size. Sluice never calls the provider's
 * {@code count_tokens} endpoint here -- that would add a network round trip to
 * every request to save money we are about to reconcile anyway.
 *
 * <p>The estimate only has to be conservative, not accurate: it sizes the hold,
 * and the hold is replaced by the provider's own count at settle time.
 */
@Component
public class TokenEstimator {

    /**
     * Roughly four characters per token for English prose, rounded up, with
     * headroom for the structural overhead of the message envelope.
     */
    private static final double CHARS_PER_TOKEN = 3.5;
    private static final long PER_MESSAGE_OVERHEAD_TOKENS = 8;
    private static final long MINIMUM_INPUT_TOKENS = 16;

    public long estimateInputTokens(JsonNode body) {
        long characters = 0;
        long messages = 0;

        JsonNode system = body.get("system");
        if (system != null) {
            characters += textLength(system);
        }
        JsonNode tools = body.get("tools");
        if (tools != null) {
            characters += textLength(tools);
        }

        JsonNode messageArray = body.get("messages");
        if (messageArray != null && messageArray.isArray()) {
            messages = messageArray.size();
            for (JsonNode message : messageArray) {
                characters += textLength(message);
            }
        }

        long fromText = (long) Math.ceil(characters / CHARS_PER_TOKEN);
        return Math.max(MINIMUM_INPUT_TOKENS, fromText + messages * PER_MESSAGE_OVERHEAD_TOKENS);
    }

    /** Total length of every string in the subtree, keys included. */
    private long textLength(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isTextual()) {
            return node.textValue().length();
        }
        if (node.isValueNode()) {
            return node.asText().length();
        }
        long total = 0;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                total += field.getKey().length() + textLength(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                total += textLength(child);
            }
        }
        return total;
    }
}
