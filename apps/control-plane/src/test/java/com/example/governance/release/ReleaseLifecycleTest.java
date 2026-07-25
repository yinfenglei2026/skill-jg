package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseLifecycleTest {
    private static final String DIGEST = "sha256:0f" + "1".repeat(62);

    @Test
    void approves_an_exact_digest_only_after_validation_and_review() {
        Release release = Release.draft("support-agent", "1.0.0", DIGEST, Instant.parse("2026-07-25T00:00:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.DRAFT);
        assertThatThrownBy(() -> release.approve("security-reviewer", Instant.parse("2026-07-25T00:02:00Z")))
                .isInstanceOf(InvalidReleaseTransitionException.class);

        release.validationPassed(Instant.parse("2026-07-25T00:01:00Z"));
        release.reviewRequired(Instant.parse("2026-07-25T00:01:30Z"));
        release.approve("security-reviewer", Instant.parse("2026-07-25T00:02:00Z"));

        assertThat(release.state()).isEqualTo(ReleaseState.APPROVED);
        assertThat(release.approval().digest()).isEqualTo(DIGEST);
        assertThat(release.transitions()).extracting(ReleaseTransition::to)
                .containsExactly(ReleaseState.VALIDATING, ReleaseState.REVIEW_REQUIRED, ReleaseState.APPROVED);
    }
}
