package com.example.governance.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ProcessCommandRunner implements CommandRunner {
    private static final List<String> MINIMAL_RUNTIME_ENVIRONMENT = List.of(
            "SystemRoot", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "TEMP", "TMP",
            "HOME", "XDG_CACHE_HOME", "TMPDIR");

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
        Process process = null;
        ExecutorService readers = null;
        try {
            Files.createDirectories(workspace);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile());
            builder.environment().clear();
            for (String name : MINIMAL_RUNTIME_ENVIRONMENT) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) {
                    builder.environment().put(name, value);
                }
            }
            if (environment != null && environment.containsKey("DOCKER_CONFIG")) {
                builder.environment().put("DOCKER_CONFIG", environment.get("DOCKER_CONFIG"));
            }
            process = builder.start();
            process.getOutputStream().close();
            AtomicLong outputBytes = new AtomicLong();
            AtomicBoolean overflow = new AtomicBoolean();
            Process runningProcess = process;
            readers = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "cosign-output-reader");
                thread.setDaemon(true);
                return thread;
            });
            Future<byte[]> stdout = readers.submit(() -> readBounded(
                    runningProcess.getInputStream(), outputBytes, maxOutputBytes, overflow, runningProcess));
            Future<byte[]> stderr = readers.submit(() -> readBounded(
                    runningProcess.getErrorStream(), outputBytes, maxOutputBytes, overflow, runningProcess));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFICATION_TIMEOUT);
            }
            byte[] stdoutBytes = completedOutput(stdout, overflow);
            byte[] stderrBytes = completedOutput(stderr, overflow);
            if (overflow.get()) {
                throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
            }
            return new CommandResult(process.exitValue(), new String(stdoutBytes, StandardCharsets.UTF_8),
                    new String(stderrBytes, StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            if (process != null) {
                destroy(process);
            }
            Thread.currentThread().interrupt();
            throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFICATION_TIMEOUT);
        } catch (IOException exception) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.COSIGN_UNAVAILABLE);
        } finally {
            if (readers != null) {
                readers.shutdownNow();
            }
        }
    }

    private byte[] readBounded(
            InputStream input,
            AtomicLong total,
            long limit,
            AtomicBoolean overflow,
            Process process) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (total.addAndGet(read) > limit) {
                    overflow.set(true);
                    destroy(process);
                    throw new OutputLimitExceededException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private byte[] completedOutput(Future<byte[]> output, AtomicBoolean overflow) {
        try {
            return output.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFICATION_TIMEOUT);
        } catch (ExecutionException exception) {
            if (overflow.get() || exception.getCause() instanceof OutputLimitExceededException) {
                throw new ArtifactVerificationException(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
            }
            throw new ArtifactVerificationException(ArtifactVerificationFailure.COSIGN_UNAVAILABLE);
        }
    }

    private void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class OutputLimitExceededException extends IOException {
    }
}
