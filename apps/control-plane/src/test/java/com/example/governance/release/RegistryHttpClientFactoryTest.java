package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryHttpClientFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void creates_a_transport_with_system_trust_when_ca_is_absent() throws Exception {
        ArtifactVerificationSettings settings = settings(null);

        assertThat(RegistryHttpClientFactory.create(settings)).isInstanceOf(JdkRegistryHttpTransport.class);
    }

    @Test
    void rejects_empty_invalid_and_multi_certificate_ca_files() throws Exception {
        Path empty = Files.createFile(tempDir.resolve("empty.pem"));
        assertThatThrownBy(() -> RegistryHttpClientFactory.create(settings(empty)))
                .isInstanceOf(IllegalArgumentException.class);
        Path invalid = Files.writeString(tempDir.resolve("invalid.pem"), "not-a-certificate");
        assertThatThrownBy(() -> RegistryHttpClientFactory.create(settings(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArtifactVerificationSettings settings(Path ca) throws Exception {
        Path key = Files.writeString(tempDir.resolve("key-" + System.nanoTime()), "key");
        return new ArtifactVerificationSettings("registry.example.internal", "robot", "secret".toCharArray(),
                ca, javaExecutable(), key,
                Set.of(URI.create("https://builder.example/internal")), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1024);
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath();
    }
}
