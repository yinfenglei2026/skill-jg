package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HarborCosignArtifactVerifierTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ArtifactVerificationRequest REQUEST = new ArtifactVerificationRequest(
            ArtifactReference.parse("oci://registry.example.internal/team/app@" + DIGEST),
            "https://git.example.internal/team/app.git",
            "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42");

    @Test
    void returns_three_ordered_evidence_records_only_after_all_checks_pass() {
        HarborRegistryClient harbor = mock(HarborRegistryClient.class);
        CosignClient cosign = mock(CosignClient.class);
        SlsaProvenancePolicy policy = mock(SlsaProvenancePolicy.class);
        CosignVerification verification = new CosignVerification("abc123", "signature", "attestation");
        SlsaProvenanceSummary summary = new SlsaProvenanceSummary(
                URI.create("https://builder.example/internal"), REQUEST.sourceRepository(), REQUEST.sourceRevision());
        when(cosign.verify(REQUEST)).thenReturn(verification);
        when(policy.verify(REQUEST, "attestation")).thenReturn(summary);

        List<VerificationEvidence> evidence =
                new HarborCosignArtifactVerifier("registry.example.internal", harbor, cosign, policy).verify(REQUEST);

        assertThat(evidence).extracting(VerificationEvidence::type)
                .containsExactly("REGISTRY_DIGEST", "COSIGN_SIGNATURE", "SLSA_PROVENANCE");
        assertThat(evidence).extracting(VerificationEvidence::subject)
                .containsExactly(REQUEST.artifact().value(), "key-sha256:abc123", summary.subject());
        assertThat(evidence).extracting(VerificationEvidence::digest).containsOnly(DIGEST);
        InOrder order = inOrder(harbor, cosign, policy);
        order.verify(harbor).verifyDigest(REQUEST.artifact());
        order.verify(cosign).verify(REQUEST);
        order.verify(policy).verify(REQUEST, "attestation");
    }

    @Test
    void rejects_wrong_registry_before_any_external_interaction() {
        HarborRegistryClient harbor = mock(HarborRegistryClient.class);
        CosignClient cosign = mock(CosignClient.class);
        SlsaProvenancePolicy policy = mock(SlsaProvenancePolicy.class);

        assertThatThrownBy(() -> new HarborCosignArtifactVerifier("other.example", harbor, cosign, policy)
                .verify(REQUEST)).isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(ArtifactVerificationFailure.REGISTRY_NOT_ALLOWED);
        verifyNoInteractions(harbor, cosign, policy);
    }
}
