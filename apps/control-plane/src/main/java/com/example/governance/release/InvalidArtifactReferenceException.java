package com.example.governance.release;

public final class InvalidArtifactReferenceException extends RuntimeException {
    public InvalidArtifactReferenceException() {
        super("Artifact reference must be an immutable oci:// reference with a lowercase SHA-256 digest");
    }
}
