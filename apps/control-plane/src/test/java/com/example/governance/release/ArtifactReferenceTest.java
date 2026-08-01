package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArtifactReferenceTest {
    private static final String DIGEST = "sha256:0f" + "a".repeat(62);

    @Test
    void extracts_registry_repository_and_immutable_digest() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://registry.example.internal/governance/support-agent@" + DIGEST);

        assertThat(artifact.registry()).isEqualTo("registry.example.internal");
        assertThat(artifact.repository()).isEqualTo("governance/support-agent");
        assertThat(artifact.digest()).isEqualTo(DIGEST);
    }

    @Test
    void matches_only_the_same_manifest_artifact_identity() {
        ArtifactReference artifact = ArtifactReference.parse(
                "oci://registry.example.internal/governance/support-agent@" + DIGEST);

        assertThat(artifact.matchesManifestUri(
                "oci://registry.example.internal/governance/support-agent")).isTrue();
        assertThat(artifact.matchesManifestUri(artifact.value())).isTrue();
        assertThat(artifact.matchesManifestUri(
                "oci://other.example.internal/governance/support-agent")).isFalse();
        assertThat(artifact.matchesManifestUri(
                "oci://registry.example.internal/other/support-agent")).isFalse();
        assertThat(artifact.matchesManifestUri(
                "oci://registry.example.internal/governance/support-agent@sha256:" + "b".repeat(64))).isFalse();
    }
}
