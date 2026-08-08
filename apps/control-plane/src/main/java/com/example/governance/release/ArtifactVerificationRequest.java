package com.example.governance.release;

import java.util.Objects;

public record ArtifactVerificationRequest(
        ArtifactReference artifact,
        String sourceRepository,
        String sourceRevision) {

    public ArtifactVerificationRequest {
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (sourceRepository == null || sourceRepository.isBlank()) {
            throw new IllegalArgumentException("sourceRepository must not be blank");
        }
        if (sourceRevision == null || sourceRevision.isBlank()) {
            throw new IllegalArgumentException("sourceRevision must not be blank");
        }
    }
}
