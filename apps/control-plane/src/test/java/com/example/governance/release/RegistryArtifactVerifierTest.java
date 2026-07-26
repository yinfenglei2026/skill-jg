package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RegistryArtifactVerifierTest {

    @Test
    void fails_fast_when_no_registry_is_configured() {
        assertThatThrownBy(() -> new RegistryArtifactVerifier(null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HARBOR_REGISTRY");
    }

    @Test
    void spring_uses_the_configured_constructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                            "governance.artifact-verification.allowed-registry=registry.example.internal")
                    .applyTo(context);
            context.register(RegistryArtifactVerifier.class);

            context.refresh();

            assertThat(context.getBean(ArtifactVerifier.class)).isInstanceOf(RegistryArtifactVerifier.class);
        }
    }
}
