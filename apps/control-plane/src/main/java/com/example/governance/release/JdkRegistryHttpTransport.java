package com.example.governance.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream input = response.body()) {
            body = readBounded(input, maxBodyBytes);
        }
        return new RegistryHttpResponse(response.statusCode(), response.headers().map(), body);
    }

    static byte[] readBounded(InputStream input, long maxBodyBytes) throws IOException {
        if (maxBodyBytes < 0 || maxBodyBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBodyBytes is outside the supported range");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBodyBytes, 8192));
        byte[] buffer = new byte[8192];
        while (output.size() <= maxBodyBytes) {
            int remaining = (int) (maxBodyBytes + 1 - output.size());
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return output.toByteArray();
            }
            output.write(buffer, 0, read);
        }
        throw new IOException("registry response exceeds configured limit");
    }
}
