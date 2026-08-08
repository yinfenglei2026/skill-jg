package com.example.governance.release;

public final class ArtifactVerificationException extends RuntimeException {
    private final ArtifactVerificationFailure failure;

    public ArtifactVerificationException(ArtifactVerificationFailure failure) {
        super(failure.publicMessage());
        this.failure = failure;
    }

    public ArtifactVerificationFailure failure() {
        return failure;
    }
}
