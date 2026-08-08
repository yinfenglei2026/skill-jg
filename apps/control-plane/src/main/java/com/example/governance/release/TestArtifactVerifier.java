package com.example.governance.release;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestArtifactVerifier implements ArtifactVerifier {
    @Override
    public List<VerificationEvidence> verify(ArtifactVerificationRequest request) {
        ArtifactReference artifact = request.artifact();
        return List.of(new VerificationEvidence("TEST_ATTESTATION", artifact.value(), artifact.digest()));
    }
}
