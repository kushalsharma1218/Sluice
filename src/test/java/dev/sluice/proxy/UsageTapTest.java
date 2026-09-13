package dev.sluice.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tap reads a byte stream it does not control: chunk boundaries land wherever
 * the network puts them, and the stream can stop at any point.
 */
class UsageTapTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String COMPLETE_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5","content":[],"usage":{"input_tokens":2145,"output_tokens":1,"cache_creation_input_tokens":128,"cache_read_input_tokens":1024}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":847}}

            event: message_stop
            data: {"type":"message_stop"}
            """;

    @Test
    @DisplayName("the final message_delta is the authoritative output count")
    void readsUsageFromACompleteStream() {
        UsageTap tap = feed(COMPLETE_STREAM, 8192);

        assertThat(tap.model()).isEqualTo("claude-opus-5");
        assertThat(tap.tokenUsage().inputTokens()).isEqualTo(2145);
        assertThat(tap.tokenUsage().outputTokens()).isEqualTo(847);
        assertThat(tap.tokenUsage().cacheCreationInputTokens()).isEqualTo(128);
        assertThat(tap.tokenUsage().cacheReadInputTokens()).isEqualTo(1024);
        assertThat(tap.hasAuthoritativeOutput()).isTrue();
        assertThat(tap.stopReason()).isEqualTo("end_turn");
    }

    @Test
    @DisplayName("chunk boundaries do not change the result")
    void isIndifferentToChunkBoundaries() {
        for (int chunkSize : new int[]{1, 3, 7, 64, 512}) {
            UsageTap tap = feed(COMPLETE_STREAM, chunkSize);
            assertThat(tap.tokenUsage().outputTokens())
                    .as("chunk size %d", chunkSize)
                    .isEqualTo(847);
            assertThat(tap.tokenUsage().inputTokens()).isEqualTo(2145);
        }
    }

    @Test
    @DisplayName("a stream cut before message_delta falls back to what was delivered")
    void estimatesOutputForATruncatedStream() {
        String truncated = COMPLETE_STREAM.substring(0, COMPLETE_STREAM.indexOf("event: message_delta"));
        UsageTap tap = feed(truncated, 64);

        assertThat(tap.hasAuthoritativeOutput())
                .as("no final usage block arrived")
                .isFalse();
        assertThat(tap.tokenUsage().inputTokens())
                .as("input is still known -- message_start carried it")
                .isEqualTo(2145);
        assertThat(tap.deliveredCharacters()).isEqualTo("Hello".length() + ", world".length());
        assertThat(tap.tokenUsage().outputTokens())
                .as("estimated from delivered text, and never below the count already seen")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a non-streaming body is read the same way")
    void readsACompleteMessageBody() throws Exception {
        UsageTap tap = new UsageTap(mapper);
        tap.accept(mapper.readTree("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                 "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":42,"output_tokens":7}}"""));

        assertThat(tap.model()).isEqualTo("claude-sonnet-5");
        assertThat(tap.tokenUsage().inputTokens()).isEqualTo(42);
        assertThat(tap.tokenUsage().outputTokens()).isEqualTo(7);
        assertThat(tap.hasAuthoritativeOutput()).isTrue();
    }

    @Test
    @DisplayName("unparseable lines are skipped rather than failing the call")
    void survivesGarbage() {
        UsageTap tap = feed("""
                event: ping
                data: {"type":"ping"}

                data: not json at all

                : a comment line

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}
                """, 16);

        assertThat(tap.tokenUsage().outputTokens()).isEqualTo(12);
        assertThat(tap.hasAuthoritativeOutput()).isTrue();
    }

    @Test
    @DisplayName("thinking and tool-input deltas count toward delivered output")
    void countsNonTextDeltas() {
        UsageTap tap = feed("""
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"abcd"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"a\\":1}"}}
                """, 32);

        assertThat(tap.deliveredCharacters()).isEqualTo(4 + "{\"a\":1}".length());
    }

    private UsageTap feed(String stream, int chunkSize) {
        UsageTap tap = new UsageTap(mapper);
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            tap.feed(bytes, offset, Math.min(chunkSize, bytes.length - offset));
        }
        tap.finish();
        return tap;
    }
}
