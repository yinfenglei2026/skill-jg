package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void fails_fast_when_no_registry_is_configured() {
        assertThatThrownBy(() -> new RegistryArtifactVerifier(null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HARBOR_REGISTRY");
    }
}
