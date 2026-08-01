package com.example.governance.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.governance.api.GovernanceService;
import com.example.governance.audit.AuditService;
import com.example.governance.release.Release;
import com.example.governance.security.Actor;
import com.example.governance.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeploymentTransactionsTest {
    private static final String DIGEST = "sha256:" + "1".repeat(64);

    @Test
    void honors_the_configured_attempt_lease_when_recovering() {
        Instant currentTime = Instant.parse("2026-08-01T00:02:00Z");
        Instant requestedAt = currentTime.minus(Duration.ofMinutes(2));
        Actor actor = new Actor("recovery-operator", "customer-operations");
        Release release = deployingRelease(requestedAt);
        DeploymentIntent intent = DeploymentIntent.pending(release, actor, requestedAt);
        intent.reconciling();
        GovernanceService governance = mock(GovernanceService.class);
        DeploymentIntentRepository deployments = mock(DeploymentIntentRepository.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(actor);
        when(governance.lockReleaseForDeployment(release.id())).thenReturn(release);
        when(deployments.findByReleaseId(release.id())).thenReturn(Optional.of(intent));
        DeploymentTransactions transactions = new DeploymentTransactions(
                governance, deployments, mock(AuditService.class), currentActor,
                Clock.fixed(currentTime, ZoneOffset.UTC), Duration.ofMinutes(1));

        DeploymentTransactions.DeploymentStart start = transactions.start(release.id());

        assertThat(start.reconcile()).isTrue();
        assertThat(start.intent().requestedAt()).isEqualTo(currentTime);
    }

    private static Release deployingRelease(Instant requestedAt) {
        Release release = Release.draft("support-agent", "1.0.0",
                "oci://registry.example.internal/governance/support-agent@" + DIGEST,
                DIGEST, Instant.parse("2026-08-01T00:00:00Z"));
        release.validationPassed("validator", Instant.parse("2026-08-01T00:00:10Z"));
        release.reviewRequired("reviewer", Instant.parse("2026-08-01T00:00:20Z"));
        release.approve("approver", Instant.parse("2026-08-01T00:00:30Z"));
        release.publish("publisher", Instant.parse("2026-08-01T00:00:40Z"));
        release.deploying("operator", requestedAt);
        return release;
    }
}
