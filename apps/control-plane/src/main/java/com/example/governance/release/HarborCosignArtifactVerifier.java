package com.example.governance.release;

import java.util.List;

public final class HarborCosignArtifactVerifier implements ArtifactVerifier {
    private final String registry;
    private final HarborRegistryClient harbor;
    private final CosignClient cosign;
    private final SlsaProvenancePolicy provenancePolicy;

    public HarborCosignArtifactVerifier(
            String registry,
            HarborRegistryClient harbor,
            CosignClient cosign,
            SlsaProvenancePolicy provenancePolicy) {
        this.registry = registry;
        this.harbor = harbor;
        this.cosign = cosign;
        this.provenancePolicy = provenancePolicy;
    }

    @Override
    public List<VerificationEvidence> verify(ArtifactVerificationRequest request) {
        ArtifactReference artifact = request.artifact();
        if (!registry.equals(artifact.registry())) {
            throw new ArtifactVerificationException(ArtifactVerificationFailure.REGISTRY_NOT_ALLOWED);
        }
        harbor.verifyDigest(artifact);
        CosignVerification verification = cosign.verify(request);
        SlsaProvenanceSummary provenance = provenancePolicy.verify(request, verification.attestationJson());
        return List.of(
                new VerificationEvidence("REGISTRY_DIGEST", artifact.value(), artifact.digest()),
                new VerificationEvidence("COSIGN_SIGNATURE",
                        "key-sha256:" + verification.publicKeyFingerprint(), artifact.digest()),
                new VerificationEvidence("SLSA_PROVENANCE", provenance.subject(), artifact.digest()));
    }
}
