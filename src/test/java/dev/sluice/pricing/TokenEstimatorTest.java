package dev.sluice.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    @DisplayName("the estimate grows with the size of the conversation")
    void scalesWithInput() throws Exception {
        long small = estimate("""
                {"model":"claude-opus-5","max_tokens":100,
                 "messages":[{"role":"user","content":"hi"}]}""");
        long large = estimate("""
                {"model":"claude-opus-5","max_tokens":100,
                 "messages":[{"role":"user","content":"%s"}]}"""
                .formatted("word ".repeat(2000)));

        assertThat(small).isLessThan(large);
        assertThat(large).isGreaterThan(2000);
    }

    @Test
    @DisplayName("system prompts and tool definitions count toward the estimate")
    void countsSystemAndTools() throws Exception {
        long withoutTools = estimate("""
                {"model":"claude-opus-5","messages":[{"role":"user","content":"hi"}]}""");
        long withTools = estimate("""
                {"model":"claude-opus-5","system":"You are a careful assistant.",
                 "tools":[{"name":"get_weather","description":"Get the weather for a city",
                           "input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],
                 "messages":[{"role":"user","content":"hi"}]}""");

        assertThat(withTools).isGreaterThan(withoutTools);
    }

    @Test
    @DisplayName("an empty conversation still estimates something")
    void neverEstimatesZero() throws Exception {
        assertThat(estimate("""
                {"model":"claude-opus-5","messages":[]}"""))
                .as("a zero estimate would mean a zero hold, which reserves nothing")
                .isPositive();
    }

    @Test
    @DisplayName("structured content blocks are counted, not skipped")
    void handlesStructuredContent() throws Exception {
        long estimate = estimate("""
                {"model":"claude-opus-5","messages":[
                  {"role":"user","content":[
                    {"type":"text","text":"%s"}]}]}""".formatted("a".repeat(400)));

        assertThat(estimate).isGreaterThan(100);
    }

    private long estimate(String json) throws Exception {
        return estimator.estimateInputTokens(mapper.readTree(json));
    }
}
