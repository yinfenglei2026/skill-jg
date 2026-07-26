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
}
