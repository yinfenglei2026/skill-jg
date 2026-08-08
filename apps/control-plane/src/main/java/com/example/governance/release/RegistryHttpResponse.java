package com.example.governance.release;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public Optional<String> firstHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public String utf8Body() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
