package com.example.governance.release;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public record ArtifactVerificationSettings(
        String registry,
        String username,
        char[] password,
        Path registryCaCert,
        Path cosignExecutable,
        Path cosignPublicKey,
        Set<URI> allowedBuilderIds,
        Duration registryTimeout,
        Duration cosignTimeout,
        long maxVerifierOutputBytes) {

    private static final Duration DEFAULT_REGISTRY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_COSIGN_TIMEOUT = Duration.ofSeconds(30);
    private static final long DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    public ArtifactVerificationSettings {
        registry = required(registry, "registry").trim();
        validateRegistry(registry);
        username = required(username, "username");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        password = password.clone();
        cosignExecutable = requireFile(cosignExecutable, "cosignExecutable");
        if (!Files.isExecutable(cosignExecutable)) {
            throw new IllegalArgumentException("cosignExecutable must be executable");
        }
        cosignPublicKey = requireFile(cosignPublicKey, "cosignPublicKey");
        if (registryCaCert != null) {
            registryCaCert = requireFile(registryCaCert, "registryCaCert");
        }
        if (allowedBuilderIds == null || allowedBuilderIds.isEmpty()) {
            throw new IllegalArgumentException("allowedBuilderIds must not be empty");
        }
        allowedBuilderIds = Set.copyOf(allowedBuilderIds);
        allowedBuilderIds.forEach(ArtifactVerificationSettings::validateBuilderId);
        registryTimeout = positiveOrDefault(registryTimeout, DEFAULT_REGISTRY_TIMEOUT, "registryTimeout");
        cosignTimeout = positiveOrDefault(cosignTimeout, DEFAULT_COSIGN_TIMEOUT, "cosignTimeout");
        if (maxVerifierOutputBytes < 0) {
            throw new IllegalArgumentException("maxVerifierOutputBytes must not be negative");
        }
        if (maxVerifierOutputBytes == 0) {
            maxVerifierOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        }
    }

    @Override
    public char[] password() {
        return password.clone();
    }

    @Override
    public String toString() {
        return "ArtifactVerificationSettings[registry=" + registry
                + ", username=" + username
                + ", password=<redacted>, registryCaCert=" + registryCaCert
                + ", cosignExecutable=" + cosignExecutable
                + ", cosignPublicKey=" + cosignPublicKey
                + ", allowedBuilderIds=" + allowedBuilderIds
                + ", registryTimeout=" + registryTimeout
                + ", cosignTimeout=" + cosignTimeout
                + ", maxVerifierOutputBytes=" + maxVerifierOutputBytes + "]";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Path requireFile(Path path, String name) {
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(name + " must be an absolute readable file");
        }
        return path.normalize();
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void validateRegistry(String registry) {
        try {
            URI uri = new URI("https://" + registry).parseServerAuthority();
            if (!registry.equals(uri.getRawAuthority()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawPath() == null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("registry must be an exact authority");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("registry must be an exact authority");
        }
    }

    private static void validateBuilderId(URI builderId) {
        if (builderId == null || !builderId.isAbsolute() || !"https".equals(builderId.getScheme())
                || builderId.getHost() == null || builderId.getRawUserInfo() != null
                || builderId.getRawQuery() != null || builderId.getRawFragment() != null) {
            throw new IllegalArgumentException("allowedBuilderIds must contain absolute HTTPS URIs");
        }
    }
}
