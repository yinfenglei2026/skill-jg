package com.example.governance.release;

import java.net.URI;

public record SlsaProvenanceSummary(URI builderId, String sourceRepository, String sourceRevision) {
    public String subject() {
        String subject = "builder:" + builderId + "|source:" + sourceRepository + "@" + sourceRevision;
        if (subject.length() > 512) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH);
        }
        return subject;
    }
}
