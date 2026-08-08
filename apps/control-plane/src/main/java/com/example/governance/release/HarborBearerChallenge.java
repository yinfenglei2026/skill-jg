package com.example.governance.release;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

public record HarborBearerChallenge(String realm, String service, String scope) {
    public static HarborBearerChallenge parse(String header, String registry, String repository) {
        try {
            if (header == null || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) {
                throw invalid();
            }
            int separator = header.indexOf(' ');
            if (separator <= 0 || !"Bearer".equalsIgnoreCase(header.substring(0, separator))) {
                throw invalid();
            }
            Map<String, String> parameters = parseParameters(header.substring(separator + 1));
            String realm = required(parameters, "realm");
            String service = required(parameters, "service");
            String scope = required(parameters, "scope");
            if (!scope.equals("repository:" + repository + ":pull")) {
                throw invalid();
            }
            URI realmUri = new URI(realm).parseServerAuthority();
            if (!"https".equals(realmUri.getScheme()) || !registry.equals(realmUri.getRawAuthority())
                    || realmUri.getHost() == null || realmUri.getRawUserInfo() != null
                    || realmUri.getRawFragment() != null) {
                throw invalid();
            }
            return new HarborBearerChallenge(realm, service, scope);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            if (exception instanceof ArtifactVerificationException verificationException) {
                throw verificationException;
            }
            throw invalid();
        }
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index < text.length()) {
            while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == ',')) {
                index++;
            }
            int equals = text.indexOf('=', index);
            if (equals <= index) {
                throw invalid();
            }
            String name = text.substring(index, equals).trim().toLowerCase(java.util.Locale.ROOT);
            index = equals + 1;
            if (index >= text.length() || text.charAt(index) != '"') {
                throw invalid();
            }
            index++;
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '\\') {
                    if (index >= text.length()) {
                        throw invalid();
                    }
                    value.append(text.charAt(index++));
                } else if (character == '"') {
                    closed = true;
                    break;
                } else {
                    value.append(character);
                }
            }
            if (!closed || result.putIfAbsent(name, value.toString()) != null) {
                throw invalid();
            }
            while (index < text.length() && text.charAt(index) == ' ') {
                index++;
            }
            if (index < text.length() && text.charAt(index) != ',') {
                throw invalid();
            }
        }
        return result;
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    private static ArtifactVerificationException invalid() {
        return new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
    }
}
