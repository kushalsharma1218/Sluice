package dev.sluice.proxy;

import dev.sluice.config.SluiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Thin pass-through to the provider.
 *
 * <p>Header handling is the whole job. The client's virtual key is stripped and
 * the real provider key substituted; everything else the provider understands is
 * forwarded untouched so that features Sluice has never heard of keep working.
 */
@Component
public class AnthropicClient {

    /** Headers Sluice owns. Anything a client sends under these names is dropped. */
    private static final Set<String> STRIPPED = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "expect",
            "x-api-key", "authorization", "accept-encoding", "upgrade", "te", "keep-alive");

    private final HttpClient http;
    private final SluiceProperties properties;

    public AnthropicClient(HttpClient http, SluiceProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    public HttpResponse<InputStream> forward(String path, byte[] body, HttpServletRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.provider().baseUrl() + path))
                .timeout(properties.provider().requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        copyClientHeaders(request, builder);

        builder.header("x-api-key", properties.provider().apiKey());
        if (request.getHeader("anthropic-version") == null) {
            builder.header("anthropic-version", properties.provider().anthropicVersion());
        }
        builder.header("content-type", "application/json");
        // Identify the hop, the way any well-behaved proxy should.
        builder.header("x-sluice-gateway", "sluice/0.1.0");

        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException e) {
            throw new ProviderException("provider request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("provider request interrupted", e);
        }
    }

    private void copyClientHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        var names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (STRIPPED.contains(lower) || lower.startsWith("x-sluice-")) {
                continue;
            }
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    /** Response headers worth relaying back to the client. */
    public static boolean isForwardableResponseHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !List.of("content-length", "transfer-encoding", "connection",
                "content-encoding", "keep-alive").contains(lower);
    }
}
