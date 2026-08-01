package com.example.governance.api;

import com.example.governance.audit.AuditEvent;
import com.example.governance.audit.AuditEventRepository;
import com.example.governance.audit.AuditService;
import com.example.governance.capability.Capability;
import com.example.governance.capability.CapabilityRepository;
import com.example.governance.capability.CapabilityType;
import com.example.governance.manifest.CapabilityPackage;
import com.example.governance.manifest.CapabilityPackageParser;
import com.example.governance.manifest.InvalidCapabilityManifestException;
import com.example.governance.release.ArtifactReference;
import com.example.governance.release.ArtifactVerifier;
import com.example.governance.release.DependencyResolver;
import com.example.governance.release.InvalidReleaseTransitionException;
import com.example.governance.release.Release;
import com.example.governance.release.ReleaseDependency;
import com.example.governance.release.ReleaseRepository;
import com.example.governance.release.VerificationEvidence;
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
    private final AuditService auditService;
    private final CurrentActor currentActor;
    private final ArtifactVerifier artifactVerifier;
    private final CapabilityPackageParser manifestParser;
    private final DependencyResolver dependencyResolver;

    @Autowired
    public GovernanceService(CapabilityRepository capabilities, ReleaseRepository releases,
                             AuditEventRepository auditEvents, AuditService auditService, CurrentActor currentActor,
                             ArtifactVerifier artifactVerifier, CapabilityPackageParser manifestParser,
                             DependencyResolver dependencyResolver) {
        this(capabilities, releases, auditEvents, auditService, currentActor, artifactVerifier, manifestParser,
                dependencyResolver, Clock.systemUTC());
    }

    GovernanceService(CapabilityRepository capabilities, ReleaseRepository releases,
                      AuditEventRepository auditEvents, AuditService auditService, CurrentActor currentActor,
                      ArtifactVerifier artifactVerifier, CapabilityPackageParser manifestParser,
                      DependencyResolver dependencyResolver, Clock clock) {
        this.capabilities = capabilities;
        this.releases = releases;
        this.auditEvents = auditEvents;
        this.auditService = auditService;
        this.currentActor = currentActor;
        this.artifactVerifier = artifactVerifier;
        this.manifestParser = manifestParser;
        this.dependencyResolver = dependencyResolver;
        this.clock = clock;
    }

    @Transactional
    public Capability createCapability(String name, String department, CapabilityType type) {
        Actor actor = currentActor.require();
        requireDepartment(actor, department);
        Capability capability = new Capability(name, department, type);
        capabilities.save(capability);
        auditService.record(actor, "CAPABILITY_CREATED", name, "ALLOW", null, now());
        return capability;
    }

    @Transactional
    public Release createRelease(String capabilityId, String version, String artifactReference, String manifestDocument) {
        Actor actor = currentActor.require();
        Capability capability = requireCapability(capabilityId);
        requireDepartment(actor, capability.department());
        CapabilityPackage manifest = manifestParser.parse(manifestDocument);
        validateManifest(capability, capabilityId, version, manifest);
        ArtifactReference artifact = ArtifactReference.parse(artifactReference);
        if (!artifact.matchesManifestUri(manifest.release().artifactUri())) {
            throw new InvalidCapabilityManifestException(
                    "release artifact reference does not match manifest artifact URI");
        }
        if (!artifact.digest().equals(manifest.canonicalDigest())) {
            throw new InvalidCapabilityManifestException("artifact digest does not match canonical manifest digest");
        }
        if (manifest.release().digest() != null && !artifact.digest().equals(manifest.release().digest())) {
            throw new InvalidCapabilityManifestException("release digest does not match artifact digest");
        }
        List<ReleaseDependency> locks = dependencyResolver.resolve(manifest, capabilityId);
        VerificationEvidence evidence = artifactVerifier.verify(artifact);
        Release release = Release.draft(capabilityId, version, artifact.value(), artifact.digest(),
                manifest.canonicalDocument(), manifest.canonicalDigest(), locks, List.of(evidence), now());
        releases.save(release);
        auditService.record(actor, "RELEASE_REGISTERED", releaseId(capabilityId, version), "ALLOW", release.digest(),
                now());
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
        try {
            transition.apply(release, actor.subject(), now());
        } catch (InvalidReleaseTransitionException exception) {
            auditService.recordDeniedTransition(actor, releaseId, release.digest(), now());
            throw exception;
        }
        auditService.record(actor, action, releaseId, decision, release.digest(), now());
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

    private void validateManifest(Capability capability, String capabilityId, String version, CapabilityPackage manifest) {
        if (!capability.department().equals(manifest.metadata().namespace())) {
            throw new InvalidCapabilityManifestException("manifest metadata.namespace does not match capability department");
        }
        if (!version.equals(manifest.metadata().version())) {
            throw new InvalidCapabilityManifestException("manifest metadata.version does not match request version");
        }
        CapabilityPackage.CapabilityDefinition manifestCapability = manifest.capability(capabilityId);
        if (!capability.type().name().equalsIgnoreCase(manifestCapability.type())) {
            throw new InvalidCapabilityManifestException("manifest capability type does not match registered capability");
        }
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
