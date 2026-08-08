package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CosignClientTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ArtifactVerificationRequest REQUEST = new ArtifactVerificationRequest(
            ArtifactReference.parse("oci://registry.example.internal/team/app@" + DIGEST),
            "https://git.example.internal/team/app.git",
            "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42");

    @TempDir
    Path tempDir;

    @Test
    void runs_exactly_signature_then_attestation_commands_without_secrets_or_insecure_flags() throws Exception {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(0, "[{\"critical\":{}}]", ""),
                new CommandResult(0, "[{\"payload\":\"e30=\"}]", ""));
        CosignClient client = client(runner);

        CosignVerification result = client.verify(REQUEST);

        assertThat(result.signatureJson()).startsWith("[");
        assertThat(runner.commands).hasSize(2);
        assertThat(runner.commands.get(0)).containsExactly(
                javaExecutable(), "verify", "--key", key().toString(), "--output", "json", REQUEST.artifact().value());
        assertThat(runner.commands.get(1)).containsExactly(
                javaExecutable(), "verify-attestation", "--key", key().toString(), "--type", "slsaprovenance1",
                "--output", "json", REQUEST.artifact().value());
        assertThat(runner.commands.toString()).doesNotContain("robot-secret", "--allow-http-registry",
                "--allow-insecure-registry", "--insecure-ignore-tlog", "--registry-password", "--registry-token");
    }

    @Test
    void maps_cosign_failures_and_malformed_outputs_to_typed_categories() throws Exception {
        assertFailure(new RecordingRunner(new CommandResult(1, "", "secret-output")),
                ArtifactVerificationFailure.SIGNATURE_INVALID);
        assertFailure(new RecordingRunner(new CommandResult(0, "not-json", ""),
                new CommandResult(0, "[]", "")), ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
        assertFailure(new RecordingRunner(new CommandResult(0, "[{\"critical\":{}}]", ""),
                new CommandResult(0, "[]", "")), ArtifactVerificationFailure.PROVENANCE_MISSING);
    }

    private void assertFailure(RecordingRunner runner, ArtifactVerificationFailure expected) throws Exception {
        assertThatThrownBy(() -> client(runner).verify(REQUEST))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(expected);
    }

    private CosignClient client(RecordingRunner runner) throws Exception {
        return new CosignClient(runner, settings());
    }

    private ArtifactVerificationSettings settings() throws Exception {
        return new ArtifactVerificationSettings("registry.example.internal", "robot", "robot-secret".toCharArray(),
                null, Path.of(javaExecutable()), key(), Set.of(java.net.URI.create("https://builder.example/internal")),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024 * 1024);
    }

    private Path key() throws Exception {
        return Files.writeString(tempDir.resolve("cosign.pub"),
                "-----BEGIN PUBLIC KEY-----\nAQID\n-----END PUBLIC KEY-----\n");
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().toString();
    }

    private static final class RecordingRunner implements CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final java.util.ArrayDeque<CommandResult> results = new java.util.ArrayDeque<>();

        private RecordingRunner(CommandResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public CommandResult run(List<String> command, Map<String, String> environment, Path workspace,
                Duration timeout, long maxOutputBytes) {
            commands.add(List.copyOf(command));
            return results.removeFirst();
        }
    }
}
