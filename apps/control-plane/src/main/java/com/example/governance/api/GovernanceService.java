package com.example.governance.api;

import com.example.governance.audit.AuditEvent;
import com.example.governance.audit.AuditEventRepository;
import com.example.governance.capability.Capability;
import com.example.governance.capability.CapabilityRepository;
import com.example.governance.capability.CapabilityType;
import com.example.governance.release.ArtifactReference;
import com.example.governance.release.ArtifactVerifier;
import com.example.governance.release.Release;
import com.example.governance.release.ReleaseRepository;
import com.example.governance.security.Actor;
import com.example.governance.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GovernanceService {
    private final Clock clock;
    private final CapabilityRepository capabilities;
    private final ReleaseRepository releases;
    private final AuditEventRepository auditEvents;
    private final CurrentActor currentActor;
    private final ArtifactVerifier artifactVerifier;

    @Autowired
    public GovernanceService(CapabilityRepository capabilities, ReleaseRepository releases,
                             AuditEventRepository auditEvents, CurrentActor currentActor, ArtifactVerifier artifactVerifier) {
        this(capabilities, releases, auditEvents, currentActor, artifactVerifier, Clock.systemUTC());
    }

    GovernanceService(CapabilityRepository capabilities, ReleaseRepository releases,
                      AuditEventRepository auditEvents, CurrentActor currentActor, ArtifactVerifier artifactVerifier, Clock clock) {
        this.capabilities = capabilities;
        this.releases = releases;
        this.auditEvents = auditEvents;
        this.currentActor = currentActor;
        this.artifactVerifier = artifactVerifier;
        this.clock = clock;
    }

    @Transactional
    public Capability createCapability(String name, String department, CapabilityType type) {
        Actor actor = currentActor.require();
        requireDepartment(actor, department);
        Capability capability = new Capability(name, department, type);
        capabilities.save(capability);
        audit(actor, "CAPABILITY_CREATED", name, "ALLOW", null);
        return capability;
    }

    @Transactional
    public Release createRelease(String capabilityId, String version, String artifactReference) {
        Actor actor = currentActor.require();
        Capability capability = requireCapability(capabilityId);
        requireDepartment(actor, capability.department());
        ArtifactReference artifact = ArtifactReference.parse(artifactReference);
        artifactVerifier.verify(artifact);
        Release release = Release.draft(capabilityId, version, artifact.value(), artifact.digest(), now());
        releases.save(release);
        audit(actor, "RELEASE_REGISTERED", releaseId(capabilityId, version), "ALLOW", release.digest());
        return release;
    }

    @Transactional
    public Release validate(String releaseId) {
        return transition(releaseId, "RELEASE_VALIDATED", "ALLOW", Release::validationPassed);
    }

    @Transactional
    public Release requireReview(String releaseId) {
        return transition(releaseId, "RELEASE_REVIEW_REQUIRED", "ALLOW", Release::reviewRequired);
    }

    @Transactional
    public Release approve(String releaseId) {
        return transition(releaseId, "RELEASE_APPROVED", "APPROVED", Release::approve);
    }

    @Transactional
    public Release publish(String releaseId) {
        return transition(releaseId, "RELEASE_PUBLISHED", "PUBLISHED", Release::publish);
    }

    @Transactional
    public Release deploying(String releaseId) {
        return transition(releaseId, "RELEASE_DEPLOYING", "DEPLOYING", Release::deploying);
    }

    @Transactional
    public Release deployed(String releaseId) {
        return transition(releaseId, "RELEASE_DEPLOYED", "DEPLOYED", Release::deployed);
    }

    @Transactional
    public Release degraded(String releaseId) {
        return transition(releaseId, "RELEASE_DEGRADED", "DEGRADED", Release::degraded);
    }

    @Transactional
    public Release failed(String releaseId) {
        return transition(releaseId, "RELEASE_FAILED", "FAILED", Release::failed);
    }

    @Transactional
    public Release reject(String releaseId) {
        return transition(releaseId, "RELEASE_REJECTED", "REJECTED", Release::reject);
    }

    @Transactional
    public Release revoke(String releaseId) {
        return transition(releaseId, "RELEASE_REVOKED", "REVOKED", Release::revoke);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> auditEvents() {
        Actor actor = currentActor.require();
        return auditEvents.findByDepartmentOrderByOccurredAtAsc(actor.department());
    }

    @Transactional(readOnly = true)
    public List<Capability> capabilities() {
        Actor actor = currentActor.require();
        return capabilities.findByDepartmentOrderByIdAsc(actor.department());
    }

    @Transactional(readOnly = true)
    public Capability capability(String capabilityId) {
        Actor actor = currentActor.require();
        Capability capability = requireCapability(capabilityId);
        requireDepartment(actor, capability.department());
        return capability;
    }

    @Transactional(readOnly = true)
    public List<Release> releases(String capabilityId) {
        Actor actor = currentActor.require();
        Capability capability = requireCapability(capabilityId);
        requireDepartment(actor, capability.department());
        return releases.findByCapabilityIdOrderByCreatedAtDesc(capabilityId);
    }

    @Transactional(readOnly = true)
    public Release release(String releaseId) {
        Actor actor = currentActor.require();
        Release release = requireRelease(releaseId);
        requireDepartment(actor, requireCapability(release.capabilityId()).department());
        return release;
    }

    private Release transition(String releaseId, String action, String decision,
                               ReleaseTransitionAction transition) {
        Actor actor = currentActor.require();
        Release release = requireRelease(releaseId);
        requireDepartment(actor, requireCapability(release.capabilityId()).department());
        transition.apply(release, actor.subject(), now());
        audit(actor, action, releaseId, decision, release.digest());
        return release;
    }

    private Capability requireCapability(String capabilityId) {
        return capabilities.findById(capabilityId)
                .orElseThrow(() -> new ResourceNotFoundException("Capability not found: " + capabilityId));
    }

    private Release requireRelease(String releaseId) {
        return releases.findById(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Release not found: " + releaseId));
    }

    private void requireDepartment(Actor actor, String department) {
        if (!actor.department().equals(department)) {
            throw new AccessDeniedException("JWT department is not authorized for this capability");
        }
    }

    private void audit(Actor actor, String action, String subject, String decision, String digest) {
        auditEvents.save(new AuditEvent(actor.subject(), actor.department(), action, subject, decision, digest, now()));
    }

    private Instant now() {
        return clock.instant();
    }

    private static String releaseId(String capabilityId, String version) {
        return capabilityId + ":" + version;
    }

    @FunctionalInterface
    private interface ReleaseTransitionAction {
        void apply(Release release, String actor, Instant occurredAt);
    }
}
