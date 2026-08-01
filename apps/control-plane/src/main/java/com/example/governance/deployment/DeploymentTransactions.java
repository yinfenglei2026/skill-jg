package com.example.governance.deployment;

import com.example.governance.api.GovernanceService;
import com.example.governance.api.ResourceNotFoundException;
import com.example.governance.audit.AuditService;
import com.example.governance.release.InvalidReleaseTransitionException;
import com.example.governance.release.Release;
import com.example.governance.release.ReleaseState;
import com.example.governance.security.Actor;
import com.example.governance.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeploymentTransactions {
    private static final EnumSet<ReleaseState> RETRYABLE_STATES = EnumSet.of(
            ReleaseState.PUBLISHED, ReleaseState.FAILED, ReleaseState.DEGRADED);

    private final GovernanceService governanceService;
    private final DeploymentIntentRepository deployments;
    private final AuditService auditService;
    private final CurrentActor currentActor;
    private final Clock clock;

    @Autowired
    public DeploymentTransactions(GovernanceService governanceService, DeploymentIntentRepository deployments,
                                  AuditService auditService, CurrentActor currentActor) {
        this(governanceService, deployments, auditService, currentActor, Clock.systemUTC());
    }

    DeploymentTransactions(GovernanceService governanceService, DeploymentIntentRepository deployments,
                           AuditService auditService, CurrentActor currentActor, Clock clock) {
        this.governanceService = governanceService;
        this.deployments = deployments;
        this.auditService = auditService;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Transactional
    public DeploymentStart start(String releaseId) {
        Actor actor = currentActor.require();
        Release release = governanceService.release(releaseId);
        DeploymentIntent existing = deployments.findByReleaseId(releaseId).orElse(null);
        if (isCompleted(release, existing) || isInProgress(release, existing)) {
            return new DeploymentStart(existing, false);
        }
        if (!RETRYABLE_STATES.contains(release.state())) {
            auditService.recordDeniedTransition(actor, releaseId, release.digest(), now());
            throw new InvalidReleaseTransitionException(release.state(), ReleaseState.DEPLOYING);
        }

        Instant requestedAt = now();
        DeploymentIntent intent;
        if (existing == null) {
            intent = deployments.save(DeploymentIntent.pending(release, actor, requestedAt));
            intent.reconciling();
        } else {
            existing.restart(release, actor, requestedAt);
            intent = existing;
        }
        auditService.record(actor, "DEPLOYMENT_REQUESTED", release.id(), "ALLOW", release.digest(), requestedAt);
        release.deploying(actor.subject(), requestedAt);
        auditService.record(actor, "RELEASE_DEPLOYING", release.id(), "DEPLOYING", release.digest(), requestedAt);
        return new DeploymentStart(intent, true);
    }

    @Transactional
    public DeploymentIntent complete(String releaseId, RuntimeObservation observation) {
        Actor actor = currentActor.require();
        Release release = governanceService.release(releaseId);
        DeploymentIntent intent = requireDeployment(releaseId);
        Instant observedAt = now();
        intent.observe(observation, observedAt);
        if (observation.status() == DeploymentStatus.READY
                && release.digest().equals(observation.digest())) {
            release.deployed(actor.subject(), observedAt);
            auditService.record(actor, "RELEASE_DEPLOYED", release.id(), "DEPLOYED", release.digest(), observedAt);
        } else {
            release.failed(actor.subject(), observedAt);
            auditService.record(actor, "RELEASE_FAILED", release.id(), "FAILED", release.digest(), observedAt);
        }
        return intent;
    }

    @Transactional
    public void fail(String releaseId) {
        Actor actor = currentActor.require();
        Release release = governanceService.release(releaseId);
        DeploymentIntent intent = requireDeployment(releaseId);
        Instant failedAt = now();
        intent.fail(failedAt);
        release.failed(actor.subject(), failedAt);
        auditService.record(actor, "DEPLOYMENT_FAILED", release.id(), "FAILED", release.digest(), failedAt);
        auditService.record(actor, "RELEASE_FAILED", release.id(), "FAILED", release.digest(), failedAt);
    }

    @Transactional(readOnly = true)
    public DeploymentIntent deployment(String releaseId) {
        governanceService.release(releaseId);
        return requireDeployment(releaseId);
    }

    private DeploymentIntent requireDeployment(String releaseId) {
        return deployments.findByReleaseId(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found: " + releaseId));
    }

    private static boolean isCompleted(Release release, DeploymentIntent intent) {
        return intent != null
                && release.state() == ReleaseState.DEPLOYED
                && intent.status() == DeploymentStatus.READY
                && release.digest().equals(intent.observedDigest());
    }

    private static boolean isInProgress(Release release, DeploymentIntent intent) {
        return intent != null
                && release.state() == ReleaseState.DEPLOYING
                && (intent.status() == DeploymentStatus.PENDING
                || intent.status() == DeploymentStatus.RECONCILING);
    }

    private Instant now() {
        return clock.instant();
    }

    public record DeploymentStart(DeploymentIntent intent, boolean reconcile) {
    }
}
