package com.example.governance.release;

public enum ArtifactVerificationFailure {
    REGISTRY_NOT_ALLOWED("Artifact registry is not allowed"),
    REGISTRY_AUTH_FAILED("Registry authentication failed"),
    REGISTRY_FORBIDDEN("Registry access is forbidden"),
    REGISTRY_UNAVAILABLE("Registry is unavailable"),
    REGISTRY_PROTOCOL_INVALID("Registry response is invalid"),
    DIGEST_MISMATCH("Artifact digest does not match"),
    COSIGN_UNAVAILABLE("Cosign is unavailable"),
    VERIFICATION_TIMEOUT("Artifact verification timed out"),
    VERIFIER_OUTPUT_INVALID("Verifier output is invalid"),
    SIGNATURE_INVALID("Artifact signature is invalid"),
    PROVENANCE_MISSING("Artifact provenance is missing"),
    PROVENANCE_INVALID("Artifact provenance is invalid"),
    PROVENANCE_POLICY_MISMATCH("Artifact provenance does not satisfy policy");

    private final String publicMessage;

    ArtifactVerificationFailure(String publicMessage) {
        this.publicMessage = publicMessage;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
