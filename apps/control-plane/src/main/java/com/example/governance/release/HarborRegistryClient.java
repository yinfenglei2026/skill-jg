package com.example.governance.release;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class HarborRegistryClient {
    private static final String OCI_ACCEPT = String.join(", ",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final RegistryHttpTransport transport;
    private final String registry;
    private final String username;
    private final char[] password;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public HarborRegistryClient(
            RegistryHttpTransport transport, String registry, String username, char[] password, Duration timeout) {
        this.transport = transport;
        this.registry = registry;
        this.username = username;
        this.password = password.clone();
        this.timeout = timeout;
    }

    public void verifyDigest(ArtifactReference artifact) {
        if (!registry.equals(artifact.registry())) {
            throw failure(ArtifactVerificationFailure.REGISTRY_NOT_ALLOWED);
        }
        URI manifestUri = URI.create("https://" + registry + "/v2/" + artifact.repository()
                + "/manifests/" + artifact.digest());
        RegistryHttpResponse anonymous = sendWithRetry(request("HEAD", manifestUri,
                Map.of("Accept", OCI_ACCEPT)));
        if (anonymous.status() == 200) {
            requireDigest(anonymous, artifact.digest());
            return;
        }
        if (anonymous.status() == 403) {
            throw failure(ArtifactVerificationFailure.REGISTRY_FORBIDDEN);
        }
        if (anonymous.status() != 401) {
            throw failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }

        HarborBearerChallenge challenge = HarborBearerChallenge.parse(
                anonymous.firstHeader("WWW-Authenticate").orElse(null), registry, artifact.repository());
        String token = fetchToken(challenge);
        RegistryHttpResponse authenticated = sendWithRetry(request("HEAD", manifestUri,
                Map.of("Accept", OCI_ACCEPT, "Authorization", "Bearer " + token)));
        if (authenticated.status() == 401) {
            throw failure(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED);
        }
        if (authenticated.status() == 403) {
            throw failure(ArtifactVerificationFailure.REGISTRY_FORBIDDEN);
        }
        if (authenticated.status() != 200) {
            throw failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }
        requireDigest(authenticated, artifact.digest());
    }

    private String fetchToken(HarborBearerChallenge challenge) {
        String query = "service=" + encode(challenge.service()) + "&scope=" + encode(challenge.scope());
        URI tokenUri = URI.create(challenge.realm() + (challenge.realm().contains("?") ? "&" : "?") + query);
        String credentials = username + ":" + new String(password);
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        RegistryHttpResponse response = sendWithRetry(request("GET", tokenUri,
                Map.of("Accept", "application/json", "Authorization", authorization)));
        if (response.status() == 401) {
            throw failure(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED);
        }
        if (response.status() == 403) {
            throw failure(ArtifactVerificationFailure.REGISTRY_FORBIDDEN);
        }
        if (response.status() != 200) {
            throw failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode token = root == null ? null : root.get("token");
            if (token == null || !token.isTextual() || token.textValue().isBlank()) {
                token = root == null ? null : root.get("access_token");
            }
            if (token == null || !token.isTextual() || token.textValue().isBlank()) {
                throw failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
            }
            return token.textValue();
        } catch (IOException exception) {
            throw failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        }
    }

    private RegistryHttpResponse sendWithRetry(RegistryHttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RegistryHttpResponse response = transport.send(request);
                if (response.status() < 500 || response.status() > 599) {
                    return response;
                }
                if (attempt == 1) {
                    throw failure(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failure(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
            } catch (IOException exception) {
                if (attempt == 1) {
                    throw failure(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
                }
            }
        }
        throw failure(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
    }

    private RegistryHttpRequest request(String method, URI uri, Map<String, String> headers) {
        return new RegistryHttpRequest(method, uri, new LinkedHashMap<>(headers), new byte[0], timeout);
    }

    private void requireDigest(RegistryHttpResponse response, String requestedDigest) {
        String resolved = response.firstHeader("Docker-Content-Digest")
                .orElseThrow(() -> failure(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID));
        if (!requestedDigest.equals(resolved)) {
            throw failure(ArtifactVerificationFailure.DIGEST_MISMATCH);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ArtifactVerificationException failure(ArtifactVerificationFailure failure) {
        return new ArtifactVerificationException(failure);
    }
}
