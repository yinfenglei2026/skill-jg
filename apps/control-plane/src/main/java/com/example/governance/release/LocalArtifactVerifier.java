package com.example.governance.release;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalArtifactVerifier implements ArtifactVerifier {
    private final String allowedRegistry;

    public LocalArtifactVerifier(
            @Value("${governance.artifact-verification.allowed-registry}") String allowedRegistry) {
        if (allowedRegistry == null || allowedRegistry.isBlank()) {
            throw new IllegalStateException(
                    "governance.artifact-verification.allowed-registry must be set (env: HARBOR_REGISTRY)");
        }
        this.allowedRegistry = allowedRegistry.trim();
    }

    @Override
    public List<VerificationEvidence> verify(ArtifactVerificationRequest request) {
        ArtifactReference artifact = request.artifact();
        if (!allowedRegistry.equals(artifact.registry())) {
            throw new ArtifactVerificationException("Artifact registry does not match the configured allowlist");
        }
        return List.of(new VerificationEvidence("LOCAL_TEST_ATTESTATION", artifact.value(), artifact.digest()));
    }
}
