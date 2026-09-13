package dev.sluice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;

@Configuration
public class HttpClientConfig {

    /**
     * HTTP/1.1 on purpose: streaming responses are relayed byte-for-byte and
     * HTTP/1.1 chunked transfer keeps that relationship one-to-one. Virtual
     * threads carry the response bodies, so a blocking read per in-flight call
     * costs almost nothing.
     */
    @Bean
    public HttpClient providerHttpClient(SluiceProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.provider().connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
