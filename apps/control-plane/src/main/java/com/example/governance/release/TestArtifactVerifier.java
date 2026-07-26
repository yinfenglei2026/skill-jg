package com.example.governance.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestArtifactVerifier implements ArtifactVerifier {
    @Override
    public void verify(ArtifactReference artifact) {
        // Tests cover registration behavior without requiring a live OCI registry.
    }
}
