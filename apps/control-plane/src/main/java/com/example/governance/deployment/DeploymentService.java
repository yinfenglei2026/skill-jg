package com.example.governance.deployment;

import com.example.governance.api.GovernanceService;
import com.example.governance.api.ResourceNotFoundException;
import com.example.governance.audit.AuditService;
import com.example.governance.release.Release;
import com.example.governance.security.Actor;
import com.example.governance.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeploymentService {
    private final GovernanceService governanceService;
    private final DeploymentIntentRepository deployments;
    private final AuditService auditService;
    private final CurrentActor currentActor;
    private final GitOpsReconciler reconciler;
    private final RuntimeObserver observer;
    private final Clock clock;

    @Autowired
    public DeploymentService(GovernanceService governanceService, DeploymentIntentRepository deployments,
                             AuditService auditService, CurrentActor currentActor, GitOpsReconciler reconciler,
                             RuntimeObserver observer) {
        this(governanceService, deployments, auditService, currentActor, reconciler, observer, Clock.systemUTC());
    }

    DeploymentService(GovernanceService governanceService, DeploymentIntentRepository deployments,
                      AuditService auditService, CurrentActor currentActor, GitOpsReconciler reconciler,
                      RuntimeObserver observer, Clock clock) {
        this.governanceService = governanceService;
        this.deployments = deployments;
        this.auditService = auditService;
        this.currentActor = currentActor;
        this.reconciler = reconciler;
        this.observer = observer;
        this.clock = clock;
    }

    @Transactional
    public DeploymentIntent deploy(String releaseId) {
        Actor actor = currentActor.require();
        Release release = governanceService.requirePublishedReleaseForDeployment(releaseId);
        DeploymentIntent intent = deployments.save(DeploymentIntent.pending(release, actor, now()));
        auditService.record(actor, "DEPLOYMENT_REQUESTED", release.id(), "ALLOW", release.digest(), now());

        release.deploying(actor.subject(), now());
        auditService.record(actor, "RELEASE_DEPLOYING", release.id(), "DEPLOYING", release.digest(), now());

        reconciler.reconcile(intent);
        RuntimeObservation observation = observer.observe(intent);
        intent.observe(observation, now());
        if (observation.status() == DeploymentStatus.READY
                && release.digest().equals(observation.digest())) {
            release.deployed(actor.subject(), now());
            auditService.record(actor, "RELEASE_DEPLOYED", release.id(), "DEPLOYED", release.digest(), now());
        } else {
            release.failed(actor.subject(), now());
            auditService.record(actor, "RELEASE_FAILED", release.id(), "FAILED", release.digest(), now());
        }
        return intent;
    }

    @Transactional(readOnly = true)
    public DeploymentIntent deployment(String releaseId) {
        governanceService.release(releaseId);
        return deployments.findByReleaseId(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found: " + releaseId));
    }

    private Instant now() {
        return clock.instant();
    }
}
