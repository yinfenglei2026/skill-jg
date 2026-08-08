package com.example.governance.release;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RegistryHttpResponse(int status, Map<String, List<String>> headers, byte[] body) {
    public RegistryHttpResponse {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        headers.forEach((name, values) -> normalized.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        headers = Map.copyOf(normalized);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public List<String> headerValues(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public String utf8Body() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
