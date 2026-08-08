package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessCommandRunnerTest {
    @TempDir
    Path workspace;

    @Test
    void preserves_arguments_and_separates_process_output() throws Exception {
        CommandResult result = runner().run(List.of(javaExecutable(), "-version"),
                Map.of("SHOULD_NOT_LEAK", "value"), workspace, Duration.ofSeconds(5), 1024 * 1024);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr() + result.stdout()).contains("version");
        assertThat(Files.list(workspace)).isEmpty();
    }

    @Test
    void returns_nonzero_exit_without_exposing_captured_output_in_exception() throws Exception {
        CommandResult result = runner().run(List.of(javaExecutable(), "-invalid-option", "secret-argument"),
                Map.of(), workspace, Duration.ofSeconds(5), 1024 * 1024);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr() + result.stdout()).doesNotContain("secret-argument");
    }

    @Test
    void rejects_output_overflow_and_non_absolute_executable() {
        assertThatThrownBy(() -> runner().run(List.of(javaExecutable(), "-version"), Map.of(), workspace,
                Duration.ofSeconds(5), 1))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
        assertThatThrownBy(() -> runner().run(List.of("java", "-version"), Map.of(), workspace,
                Duration.ofSeconds(5), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminates_a_running_process_as_soon_as_output_overflows() throws Exception {
        Instant started = Instant.now();

        assertThatThrownBy(() -> runner().run(List.of(javaExecutable(), "-cp",
                System.getProperty("java.class.path"), OutputFloodProcess.class.getName()),
                Map.of(), workspace, Duration.ofSeconds(30), 1024))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(Files.list(workspace)).isEmpty();
    }

    private ProcessCommandRunner runner() {
        return new ProcessCommandRunner();
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().toString();
    }

    public static final class OutputFloodProcess {
        public static void main(String[] args) {
            byte[] output = new byte[8192];
            while (true) {
                System.out.write(output, 0, output.length);
            }
        }
    }
}
