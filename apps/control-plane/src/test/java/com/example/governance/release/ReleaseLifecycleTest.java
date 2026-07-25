package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseLifecycleTest {
    private static final String DIGEST = "sha256:0f" + "1".repeat(62);

    @Test
    void approves_an_exact_digest_only_after_validation_and_review() {
        Release release = Release.draft("support-agent", "1.0.0",
                "oci://registry.example.internal/governance/support-agent@" + DIGEST,
                DIGEST, Instant.parse("2026-07-25T00:00:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.DRAFT);
        assertThatThrownBy(() -> release.approve("security-reviewer", Instant.parse("2026-07-25T00:02:00Z")))
                .isInstanceOf(InvalidReleaseTransitionException.class);

        release.validationPassed("validation-bot", Instant.parse("2026-07-25T00:01:00Z"));
        release.reviewRequired("security-reviewer", Instant.parse("2026-07-25T00:01:30Z"));
        release.approve("security-reviewer", Instant.parse("2026-07-25T00:02:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.APPROVED);
        assertThat(release.approval().digest()).isEqualTo(DIGEST);
        assertThat(release.transitions()).extracting(ReleaseTransition::to)
                .containsExactly(ReleaseState.VALIDATING, ReleaseState.REVIEW_REQUIRED, ReleaseState.APPROVED);
    }

    @Test
    void records_deployment_failure_recovery_and_revocation_transitions() {
        Release release = Release.draft("support-agent", "1.0.0",
                "oci://registry.example.internal/governance/support-agent@" + DIGEST,
                DIGEST, Instant.parse("2026-07-25T00:00:00Z"));

        release.validationPassed("reviewer", Instant.parse("2026-07-25T00:01:00Z"));
        release.reviewRequired("reviewer", Instant.parse("2026-07-25T00:02:00Z"));
        release.approve("approver", Instant.parse("2026-07-25T00:03:00Z"));
        release.publish("release-bot", Instant.parse("2026-07-25T00:04:00Z"));
        release.deploying("runtime-controller", Instant.parse("2026-07-25T00:05:00Z"));
        release.deployed("runtime-controller", Instant.parse("2026-07-25T00:06:00Z"));
        release.degraded("runtime-controller", Instant.parse("2026-07-25T00:07:00Z"));
        release.failed("runtime-controller", Instant.parse("2026-07-25T00:08:00Z"));
        release.deploying("runtime-controller", Instant.parse("2026-07-25T00:09:00Z"));
        release.deployed("runtime-controller", Instant.parse("2026-07-25T00:10:00Z"));
        release.revoke("runtime-controller", Instant.parse("2026-07-25T00:11:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.REVOKED);
        assertThat(release.transitions()).extracting(ReleaseTransition::to)
                .containsExactly(ReleaseState.VALIDATING, ReleaseState.REVIEW_REQUIRED, ReleaseState.APPROVED,
                        ReleaseState.PUBLISHED, ReleaseState.DEPLOYING, ReleaseState.DEPLOYED,
                        ReleaseState.DEGRADED, ReleaseState.FAILED, ReleaseState.DEPLOYING,
                        ReleaseState.DEPLOYED, ReleaseState.REVOKED);
    }

    @Test
    void allows_a_reviewer_to_reject_a_candidate_before_approval() {
        Release release = Release.draft("support-agent", "1.0.1",
                "oci://registry.example.internal/governance/support-agent@" + DIGEST,
                DIGEST, Instant.parse("2026-07-25T00:00:00Z"));

        release.validationPassed("reviewer", Instant.parse("2026-07-25T00:01:00Z"));
        release.reject("reviewer", Instant.parse("2026-07-25T00:02:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.REJECTED);
    }
}
