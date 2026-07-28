package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalArtifactVerifierTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void fails_fast_when_allowed_registry_is_blank() {
        assertThatThrownBy(() -> new LocalArtifactVerifier("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-registry");
    }

    @Test
    void returns_local_attestation_without_registry_access() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://registry.example.internal/governance/support-agent@" + DIGEST);

        VerificationEvidence evidence = new LocalArtifactVerifier("registry.example.internal").verify(artifact);

        assertThat(evidence.type()).isEqualTo("LOCAL_TEST_ATTESTATION");
        assertThat(evidence.subject()).isEqualTo(artifact.value());
        assertThat(evidence.digest()).isEqualTo(DIGEST);
    }

    @Test
    void rejects_artifacts_from_an_unconfigured_registry() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://other.example.internal/governance/support-agent@" + DIGEST);

        assertThatThrownBy(() -> new LocalArtifactVerifier("registry.example.internal").verify(artifact))
                .isInstanceOf(ArtifactVerificationException.class);
    }
}
