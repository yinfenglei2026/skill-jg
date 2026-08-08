package com.example.governance.release;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record RegistryHttpRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        byte[] body,
        Duration timeout) {

    public RegistryHttpRequest {
        method = Objects.requireNonNull(method, "method");
        uri = Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
        timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
