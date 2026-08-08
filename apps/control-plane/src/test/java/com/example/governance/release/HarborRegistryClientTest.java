package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HarborRegistryClientTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ArtifactReference ARTIFACT = ArtifactReference.parse(
            "oci://registry.example.internal/team/app@" + DIGEST);

    @Test
    void accepts_a_direct_digest_response() {
        ScriptedTransport transport = new ScriptedTransport(response(200,
                Map.of("Docker-Content-Digest", List.of(DIGEST)), ""));

        client(transport).verifyDigest(ARTIFACT);

        assertThat(transport.requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("HEAD");
            assertThat(request.uri()).isEqualTo(URI.create(
                    "https://registry.example.internal/v2/team/app/manifests/" + DIGEST));
            assertThat(request.headers()).doesNotContainKey("Authorization");
        });
    }

    @Test
    void authenticates_with_basic_only_at_the_token_endpoint_then_uses_bearer() {
        ScriptedTransport transport = new ScriptedTransport(
                response(401, Map.of("WWW-Authenticate", List.of(
                        "Bearer realm=\"https://registry.example.internal/service/token\","
                                + "service=\"harbor\",scope=\"repository:team/app:pull\"")), ""),
                response(200, Map.of(), "{\"access_token\":\"issued-token\"}"),
                response(200, Map.of("docker-content-digest", List.of(DIGEST)), ""));

        client(transport).verifyDigest(ARTIFACT);

        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.get(1).uri().getRawQuery())
                .contains("service=harbor", "scope=repository%3Ateam%2Fapp%3Apull");
        assertThat(transport.requests.get(1).headers().get("Authorization")).startsWith("Basic ");
        assertThat(transport.requests.get(2).headers().get("Authorization")).isEqualTo("Bearer issued-token");
    }

    @Test
    void accepts_the_token_response_field() {
        ScriptedTransport transport = authenticated("{\"token\":\"issued-token\"}", DIGEST);

        client(transport).verifyDigest(ARTIFACT);

        assertThat(transport.requests.get(2).headers().get("Authorization")).isEqualTo("Bearer issued-token");
    }

    @Test
    void maps_auth_forbidden_protocol_and_digest_failures() {
        assertFailure(new ScriptedTransport(response(401, challenge(), ""), response(401, Map.of(), "")),
                ArtifactVerificationFailure.REGISTRY_AUTH_FAILED);
        assertFailure(new ScriptedTransport(response(403, Map.of(), "")),
                ArtifactVerificationFailure.REGISTRY_FORBIDDEN);
        assertFailure(authenticated("not-json", DIGEST), ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        assertFailure(new ScriptedTransport(response(200, Map.of(), "")),
                ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
        assertFailure(new ScriptedTransport(response(200,
                Map.of("Docker-Content-Digest", List.of("sha256:" + "b".repeat(64))), "")),
                ArtifactVerificationFailure.DIGEST_MISMATCH);
    }

    @Test
    void retries_one_transport_failure_or_server_error_only() {
        ScriptedTransport afterIo = new ScriptedTransport(new IOException("robot-secret"),
                response(200, Map.of("Docker-Content-Digest", List.of(DIGEST)), ""));
        client(afterIo).verifyDigest(ARTIFACT);
        assertThat(afterIo.requests).hasSize(2);

        ScriptedTransport afterServerError = new ScriptedTransport(response(503, Map.of(), ""),
                response(200, Map.of("Docker-Content-Digest", List.of(DIGEST)), ""));
        client(afterServerError).verifyDigest(ARTIFACT);
        assertThat(afterServerError.requests).hasSize(2);

        ScriptedTransport exhausted = new ScriptedTransport(new IOException("issued-token"),
                new IOException("robot-secret"));
        assertThatThrownBy(() -> client(exhausted).verifyDigest(ARTIFACT))
                .isInstanceOf(ArtifactVerificationException.class)
                .hasMessageNotContaining("robot-secret")
                .hasMessageNotContaining("issued-token")
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(ArtifactVerificationFailure.REGISTRY_UNAVAILABLE);
        assertThat(exhausted.requests).hasSize(2);
    }

    private void assertFailure(ScriptedTransport transport, ArtifactVerificationFailure expected) {
        assertThatThrownBy(() -> client(transport).verifyDigest(ARTIFACT))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(expected);
    }

    private HarborRegistryClient client(ScriptedTransport transport) {
        return new HarborRegistryClient(transport, "registry.example.internal",
                "robot$governance", "robot-secret".toCharArray(), Duration.ofSeconds(2));
    }

    private ScriptedTransport authenticated(String tokenBody, String digest) {
        return new ScriptedTransport(response(401, challenge(), ""),
                response(200, Map.of(), tokenBody),
                response(200, Map.of("Docker-Content-Digest", List.of(digest)), ""));
    }

    private Map<String, List<String>> challenge() {
        return Map.of("WWW-Authenticate", List.of(
                "Bearer realm=\"https://registry.example.internal/service/token\","
                        + "service=\"harbor\",scope=\"repository:team/app:pull\""));
    }

    private RegistryHttpResponse response(int status, Map<String, List<String>> headers, String body) {
        return new RegistryHttpResponse(status, headers, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class ScriptedTransport implements RegistryHttpTransport {
        private final ArrayDeque<Object> results = new ArrayDeque<>();
        private final List<RegistryHttpRequest> requests = new ArrayList<>();

        private ScriptedTransport(Object... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public RegistryHttpResponse send(RegistryHttpRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object result = results.removeFirst();
            if (result instanceof IOException exception) {
                throw exception;
            }
            if (result instanceof InterruptedException exception) {
                throw exception;
            }
            return (RegistryHttpResponse) result;
        }
    }
}
