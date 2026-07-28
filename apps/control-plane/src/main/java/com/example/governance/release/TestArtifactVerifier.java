package com.example.governance.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestArtifactVerifier implements ArtifactVerifier {
    @Override
    public VerificationEvidence verify(ArtifactReference artifact) {
        return new VerificationEvidence("TEST_ATTESTATION", artifact.value(), artifact.digest());
    }
}
