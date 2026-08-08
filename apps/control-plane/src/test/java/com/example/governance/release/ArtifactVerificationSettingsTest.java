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

class ArtifactVerificationSettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void applies_defaults_and_defensively_copies_secrets_and_builders() throws Exception {
        char[] password = "robot-secret".toCharArray();
        Set<URI> builders = new java.util.HashSet<>(Set.of(URI.create("https://builder.example/internal")));
        ArtifactVerificationSettings settings = settings(password, builders, null, null, null, 0);

        password[0] = 'X';
        builders.clear();
        char[] exposed = settings.password();
        exposed[0] = 'Y';

        assertThat(settings.password()).containsExactly("robot-secret".toCharArray());
        assertThat(settings.allowedBuilderIds()).containsExactly(URI.create("https://builder.example/internal"));
        assertThat(settings.allowedBuilderIds()).isUnmodifiable();
        assertThat(settings.registryTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.cosignTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.maxVerifierOutputBytes()).isEqualTo(1024 * 1024);
        assertThat(settings.toString()).doesNotContain("robot-secret");
    }

    @Test
    void rejects_missing_required_settings() throws Exception {
        ArtifactVerificationSettings valid = settings("secret".toCharArray(),
                Set.of(URI.create("https://builder.example/internal")), null, null, null, 1024);

        assertThatThrownBy(() -> copy(valid, " ", valid.username(), valid.password(), valid.registryCaCert(),
                valid.cosignExecutable(), valid.cosignPublicKey(), valid.allowedBuilderIds()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(valid, valid.registry(), " ", valid.password(), valid.registryCaCert(),
                valid.cosignExecutable(), valid.cosignPublicKey(), valid.allowedBuilderIds()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(valid, valid.registry(), valid.username(), new char[0], valid.registryCaCert(),
                valid.cosignExecutable(), valid.cosignPublicKey(), valid.allowedBuilderIds()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(valid, valid.registry(), valid.username(), valid.password(),
                valid.registryCaCert(), valid.cosignExecutable(), valid.cosignPublicKey(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_non_authority_registries_and_invalid_builder_ids() throws Exception {
        for (String registry : new String[] {
                "https://registry.example.internal", "registry.example.internal/path",
                "user@registry.example.internal", "registry.example.internal:0"
        }) {
            assertThatThrownBy(() -> settings(registry, Set.of(URI.create("https://builder.example/internal"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings("registry.example.internal", Set.of(URI.create("relative"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unreadable_or_relative_verification_files() throws Exception {
        Path executable = javaExecutable();
        Path key = Files.writeString(tempDir.resolve("cosign.pub"), "key");

        assertThatThrownBy(() -> new ArtifactVerificationSettings(
                "registry.example.internal", "robot", "secret".toCharArray(), null,
                Path.of("cosign"), key, Set.of(URI.create("https://builder.example/internal")),
                null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactVerificationSettings(
                "registry.example.internal", "robot", "secret".toCharArray(), null,
                executable, tempDir.resolve("missing.pub").toAbsolutePath(),
                Set.of(URI.create("https://builder.example/internal")), null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exception_exposes_typed_failure_and_only_stable_public_message() {
        ArtifactVerificationException exception =
                new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED);

        assertThat(exception.failure()).isEqualTo(ArtifactVerificationFailure.REGISTRY_AUTH_FAILED);
        assertThat(exception.getMessage()).isEqualTo("Registry authentication failed");
    }

    private ArtifactVerificationSettings settings(
            char[] password, Set<URI> builders, Duration registryTimeout,
            Duration cosignTimeout, Path ca, long outputLimit) throws Exception {
        return new ArtifactVerificationSettings(
                "registry.example.internal:8443", "robot$governance", password, ca,
                javaExecutable(), Files.writeString(tempDir.resolve("cosign-" + System.nanoTime() + ".pub"), "key"),
                builders, registryTimeout, cosignTimeout, outputLimit);
    }

    private ArtifactVerificationSettings settings(String registry, Set<URI> builders) throws Exception {
        return new ArtifactVerificationSettings(
                registry, "robot", "secret".toCharArray(), null, javaExecutable(),
                Files.writeString(tempDir.resolve("key-" + System.nanoTime()), "key"), builders,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);
    }

    private ArtifactVerificationSettings copy(
            ArtifactVerificationSettings original, String registry, String username, char[] password,
            Path ca, Path executable, Path key, Set<URI> builders) {
        return new ArtifactVerificationSettings(registry, username, password, ca, executable, key, builders,
                original.registryTimeout(), original.cosignTimeout(), original.maxVerifierOutputBytes());
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath();
    }
}
