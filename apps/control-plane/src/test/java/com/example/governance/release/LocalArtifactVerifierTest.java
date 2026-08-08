package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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

        List<VerificationEvidence> evidence = new LocalArtifactVerifier("registry.example.internal").verify(request(artifact));

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("LOCAL_TEST_ATTESTATION");
            assertThat(item.subject()).isEqualTo(artifact.value());
            assertThat(item.digest()).isEqualTo(DIGEST);
        });
        assertThat(evidence).isUnmodifiable();
    }

    @Test
    void rejects_artifacts_from_an_unconfigured_registry() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://other.example.internal/governance/support-agent@" + DIGEST);

        assertThatThrownBy(() -> new LocalArtifactVerifier("registry.example.internal").verify(request(artifact)))
                .isInstanceOf(ArtifactVerificationException.class);
    }

    @Test
    void rejects_invalid_verification_requests() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://registry.example.internal/governance/support-agent@" + DIGEST);

        assertThatThrownBy(() -> new ArtifactVerificationRequest(null, "repo", "revision"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ArtifactVerificationRequest(artifact, " ", "revision"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactVerificationRequest(artifact, "repo", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArtifactVerificationRequest request(ArtifactReference artifact) {
        return new ArtifactVerificationRequest(
                artifact,
                "https://git.example.internal/governance/support-agent.git",
                "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42");
    }
}
