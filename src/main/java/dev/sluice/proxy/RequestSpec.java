package dev.sluice.proxy;

import com.fasterxml.jackson.databind.JsonNode;

/** The handful of fields Sluice needs to read out of an otherwise opaque body. */
public record RequestSpec(String model, long maxTokens, boolean stream) {

    public static RequestSpec parse(JsonNode body, long defaultMaxTokens) {
        String model = body.path("model").asText(null);
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("'model' is required");
        }
        JsonNode maxTokensNode = body.get("max_tokens");
        long maxTokens = maxTokensNode == null || !maxTokensNode.canConvertToLong()
                ? defaultMaxTokens
                : maxTokensNode.asLong();
        if (maxTokens <= 0) {
            throw new InvalidRequestException("'max_tokens' must be positive");
        }
        return new RequestSpec(model, maxTokens, body.path("stream").asBoolean(false));
    }
}
