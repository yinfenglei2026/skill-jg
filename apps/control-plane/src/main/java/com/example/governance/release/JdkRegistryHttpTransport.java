package com.example.governance.release;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class JdkRegistryHttpTransport implements RegistryHttpTransport {
    private final HttpClient client;
    private final long maxBodyBytes;

    public JdkRegistryHttpTransport(HttpClient client, long maxBodyBytes) {
        this.client = client;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public RegistryHttpResponse send(RegistryHttpRequest request) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(request.uri().getScheme())) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > maxBodyBytes) {
            throw new IOException("registry response exceeds configured limit");
        }
        return new RegistryHttpResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
