package com.example.governance.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(
            List<String> command,
            Map<String, String> environment,
            Path workspace,
            Duration timeout,
            long maxOutputBytes) {
        if (command == null || command.isEmpty() || !Path.of(command.get(0)).isAbsolute()) {
            throw new IllegalArgumentException("command must start with an absolute executable");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        Path stdout = workspace.resolve("stdout-" + System.nanoTime());
        Path stderr = workspace.resolve("stderr-" + System.nanoTime());
        Process process = null;
        try {
            Files.createDirectories(workspace);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            builder.environment().clear();
            if (environment != null && environment.containsKey("DOCKER_CONFIG")) {
                builder.environment().put("DOCKER_CONFIG", environment.get("DOCKER_CONFIG"));
            }
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFICATION_TIMEOUT);
            }
            long outputBytes = Files.size(stdout) + Files.size(stderr);
            if (outputBytes > maxOutputBytes) {
                destroy(process);
                throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
            }
            return new CommandResult(process.exitValue(), read(stdout), read(stderr));
        } catch (InterruptedException exception) {
            if (process != null) {
                destroy(process);
            }
            Thread.currentThread().interrupt();
            throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFICATION_TIMEOUT);
        } catch (IOException exception) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.COSIGN_UNAVAILABLE);
        } finally {
            delete(stdout);
            delete(stderr);
        }
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup; the workspace is operation-scoped and caller owns it.
        }
    }
}
