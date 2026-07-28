package com.example.governance.release;

public interface ArtifactVerifier {
    VerificationEvidence verify(ArtifactReference artifact);
}
