package com.example.governance.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DockerAuthWorkspace implements AutoCloseable {
    private final Path root;
    private final Path configFile;
    private final char[] password;

    DockerAuthWorkspace(Path root, Path configFile, char[] password) {
        this.root = root;
        this.configFile = configFile;
        this.password = password.clone();
    }

    public Path root() {
        return root;
    }

    public Path configFile() {
        return configFile;
    }

    @Override
    public void close() {
        java.util.Arrays.fill(password, '\0');
        try (var files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    @Override
    public String toString() {
        return "DockerAuthWorkspace[root=" + root + "]";
    }
}
