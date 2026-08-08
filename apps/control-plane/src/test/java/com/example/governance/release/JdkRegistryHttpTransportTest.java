package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JdkRegistryHttpTransportTest {
    @Test
    void rejects_non_https_requests_before_network_access() {
        JdkRegistryHttpTransport transport = new JdkRegistryHttpTransport(
                java.net.http.HttpClient.newHttpClient(), 64 * 1024);

        assertThatThrownBy(() -> transport.send(new RegistryHttpRequest(
                        "GET", URI.create("http://127.0.0.1:1/token"), Map.of(), new byte[0], Duration.ofSeconds(1))))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(ArtifactVerificationFailure.REGISTRY_PROTOCOL_INVALID);
    }

    @Test
    void rejects_redirects_by_factory_policy() throws Exception {
        Path key = Files.createTempFile("cosign", ".pub");
        ArtifactVerificationSettings settings = new ArtifactVerificationSettings(
                "registry.example.internal", "robot", "secret".toCharArray(), null,
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath(), key,
                java.util.Set.of(URI.create("https://builder.example/internal")),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);

        assertThatThrownBy(() -> RegistryHttpClientFactory.create(settings)
                .send(new RegistryHttpRequest("GET", URI.create("https://127.0.0.1:1/"), Map.of(), new byte[0],
                        Duration.ofMillis(50))))
                .isInstanceOfAny(java.io.IOException.class, ArtifactVerificationException.class);
    }
}
