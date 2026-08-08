package com.example.governance.release;

import java.util.List;

public interface ArtifactVerifier {
    List<VerificationEvidence> verify(ArtifactVerificationRequest request);
}
