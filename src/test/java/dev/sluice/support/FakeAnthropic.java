package dev.sluice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for the provider. Real HTTP on a real socket, so the proxy is
 * exercised end to end -- header handling, chunked streaming, disconnects --
 * without a network call or an API key.
 */
public class FakeAnthropic implements AutoCloseable {

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();

    /** Released once the server has written the first chunk of a streamed body. */
    public final CountDownLatch firstChunkSent = new CountDownLatch(1);
    /** Counts down when a streamed response finished writing (or failed to). */
    public final CountDownLatch streamFinished = new CountDownLatch(1);

    public FakeAnthropic() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public void respondWith(Responder next) {
        responder.set(next);
    }

    /** A complete non-streaming message with the given usage block. */
    public void respondWithMessage(String model, long inputTokens, long outputTokens) {
        respondWith(exchange -> {
            byte[] body = """
                    {"id":"msg_test","type":"message","role":"assistant","model":"%s",
                     "content":[{"type":"text","text":"hello"}],
                     "stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":%d,"output_tokens":%d}}
                    """.formatted(model, inputTokens, outputTokens).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /**
     * An SSE stream. {@code chunks} are sent in order with a flush between each;
     * when {@code includeFinalUsage} is false the stream stops before
     * {@code message_delta}, which is what a killed connection looks like.
     */
    public void respondWithStream(String model, long inputTokens, List<String> textChunks,
                                  long finalOutputTokens, boolean includeFinalUsage,
                                  long perChunkDelayMillis) {
        respondWith(exchange -> {
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                send(out, "message_start", """
                        {"type":"message_start","message":{"id":"msg_test","type":"message",
                         "role":"assistant","model":"%s","content":[],
                         "usage":{"input_tokens":%d,"output_tokens":1}}}"""
                        .formatted(model, inputTokens));
                send(out, "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,"
                                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
                firstChunkSent.countDown();

                for (String chunk : textChunks) {
                    if (perChunkDelayMillis > 0) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(perChunkDelayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    send(out, "content_block_delta", """
                            {"type":"content_block_delta","index":0,
                             "delta":{"type":"text_delta","text":"%s"}}""".formatted(chunk));
                }
                send(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

                if (includeFinalUsage) {
                    send(out, "message_delta", """
                            {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                             "usage":{"output_tokens":%d}}""".formatted(finalOutputTokens));
                    send(out, "message_stop", "{\"type\":\"message_stop\"}");
                }
            } catch (IOException e) {
                // The proxy hung up. That is the scenario under test.
            } finally {
                streamFinished.countDown();
            }
        });
    }

    public void respondWithError(int status, String type, String message) {
        respondWith(exchange -> {
            byte[] body = """
                    {"type":"error","error":{"type":"%s","message":"%s"}}"""
                    .formatted(type, message).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static void send(OutputStream out, String event, String data) throws IOException {
        // SSE data must be a single line; the fixtures above are written multi-line
        // for readability, so collapse them the way a real server would emit them.
        String oneLine = data.replaceAll("\\s*\\n\\s*", "");
        out.write(("event: " + event + "\ndata: " + oneLine + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, List<String>> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), new ArrayList<>(v)));
        requests.add(new RecordedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), headers,
                new String(body, StandardCharsets.UTF_8)));

        Responder current = responder.get();
        if (current == null) {
            byte[] fallback = "{\"type\":\"error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, fallback.length);
            exchange.getResponseBody().write(fallback);
            exchange.close();
            return;
        }
        try {
            current.respond(exchange);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    @FunctionalInterface
    public interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    public record RecordedRequest(String method, String path,
                                  Map<String, List<String>> headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
