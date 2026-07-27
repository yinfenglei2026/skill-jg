package com.example.governance.manifest;

import java.util.List;

public record CapabilityPackage(
        String apiVersion,
        String kind,
        Metadata metadata,
        ReleaseDescriptor release,
        List<CapabilityDefinition> capabilities,
        String canonicalDocument,
        String canonicalDigest) {

    public CapabilityPackage {
        capabilities = List.copyOf(capabilities);
    }

    public CapabilityDefinition capability(String id) {
        return capabilities.stream()
                .filter(capability -> capability.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new InvalidCapabilityManifestException("capability not found: " + id));
    }

    public record Metadata(String name, String namespace, String version) {
    }

    public record ReleaseDescriptor(String digest) {
    }

    public record CapabilityDefinition(
            String id,
            String type,
            List<DependencyDefinition> dependencies,
            NetworkDefinition network,
            List<SecretDefinition> secrets) {

        public CapabilityDefinition {
            dependencies = List.copyOf(dependencies);
            secrets = List.copyOf(secrets);
        }
    }

    public record DependencyDefinition(
            String id,
            String type,
            String version,
            String digest,
            String importPath) {
    }

    public record NetworkDefinition(boolean defaultDeny) {
    }

    public record SecretDefinition(String name, String provider, String key, String version) {
    }
}
