package com.example.governance.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.governance.release.Release;
import com.example.governance.security.Actor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeploymentIntentTest {
    private static final String DIGEST = "sha256:" + "1".repeat(64);

    @Test
    void restarts_a_failed_attempt_without_changing_identity_or_digest() {
        Release release = release("1.0.0", DIGEST);
        DeploymentIntent intent = DeploymentIntent.pending(
                release, new Actor("first-operator", "customer-operations"),
                Instant.parse("2026-08-01T00:00:00Z"));
        String id = intent.id();
        intent.reconciling();
        intent.observe(new RuntimeObservation(DIGEST, DeploymentStatus.FAILED),
                Instant.parse("2026-08-01T00:01:00Z"));

        intent.restart(release, new Actor("retry-operator", "customer-operations"),
                Instant.parse("2026-08-01T00:02:00Z"));

        assertThat(intent.id()).isEqualTo(id);
        assertThat(intent.desiredDigest()).isEqualTo(DIGEST);
        assertThat(intent.status()).isEqualTo(DeploymentStatus.RECONCILING);
        assertThat(intent.observedDigest()).isNull();
        assertThat(intent.observedAt()).isNull();
        assertThat(intent.requestedActor()).isEqualTo("retry-operator");
        assertThat(intent.requestedAt()).isEqualTo(Instant.parse("2026-08-01T00:02:00Z"));
    }

    @Test
    void refuses_to_reuse_an_intent_for_a_different_digest() {
        Release original = release("1.0.0", DIGEST);
        DeploymentIntent intent = DeploymentIntent.pending(
                original, new Actor("operator", "customer-operations"),
                Instant.parse("2026-08-01T00:00:00Z"));
        Release changed = release("1.0.1", "sha256:" + "2".repeat(64));

        assertThatThrownBy(() -> intent.restart(changed,
                new Actor("operator", "customer-operations"),
                Instant.parse("2026-08-01T00:02:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Deployment intent digest cannot change");
    }

    private static Release release(String version, String digest) {
        return Release.draft("support-agent", version,
                "oci://registry.example.internal/governance/support-agent@" + digest,
                digest, Instant.parse("2026-08-01T00:00:00Z"));
    }
}
