package com.example.governance.api;

import com.example.governance.audit.AuditEvent;
import com.example.governance.capability.Capability;
import com.example.governance.capability.CapabilityType;
import com.example.governance.release.Release;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GovernanceService {
    private final Clock clock;
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    private final Map<String, Release> releases = new LinkedHashMap<>();
    private final List<AuditEvent> auditEvents = new ArrayList<>();

    public GovernanceService() {
        this(Clock.systemUTC());
    }

    GovernanceService(Clock clock) {
        this.clock = clock;
    }

    public synchronized Capability createCapability(String name, String department, CapabilityType type) {
        Capability capability = new Capability(name, department, type);
        capabilities.put(name, capability);
        audit("CAPABILITY_CREATED", name, null);
        return capability;
    }

    public synchronized Release createRelease(String capabilityId, String version, String digest) {
        requireCapability(capabilityId);
        Release release = Release.draft(capabilityId, version, digest, now());
        releases.put(releaseId(capabilityId, version), release);
        audit("RELEASE_CREATED", releaseId(capabilityId, version), digest);
        return release;
    }

    public synchronized Release validate(String releaseId) {
        Release release = requireRelease(releaseId);
        release.validationPassed(now());
        audit("RELEASE_VALIDATED", releaseId, release.digest());
        return release;
    }

    public synchronized Release requireReview(String releaseId) {
        Release release = requireRelease(releaseId);
        release.reviewRequired(now());
        audit("RELEASE_REVIEW_REQUIRED", releaseId, release.digest());
        return release;
    }

    public synchronized Release approve(String releaseId, String actor, String digest) {
        Release release = requireRelease(releaseId);
        if (!release.digest().equals(digest)) {
            throw new DigestMismatchException();
        }
        release.approve(actor, now());
        audit("RELEASE_APPROVED", releaseId, release.digest());
        return release;
    }

    public synchronized Release publish(String releaseId) {
        Release release = requireRelease(releaseId);
        release.publish(now());
        audit("RELEASE_PUBLISHED", releaseId, release.digest());
        return release;
    }

    public synchronized List<AuditEvent> auditEvents() {
        return List.copyOf(auditEvents);
    }

    private Capability requireCapability(String capabilityId) {
        Capability capability = capabilities.get(capabilityId);
        if (capability == null) {
            throw new ResourceNotFoundException("Capability not found: " + capabilityId);
        }
        return capability;
    }

    private Release requireRelease(String releaseId) {
        Release release = releases.get(releaseId);
        if (release == null) {
            throw new ResourceNotFoundException("Release not found: " + releaseId);
        }
        return release;
    }

    private void audit(String action, String subject, String digest) {
        auditEvents.add(new AuditEvent(action, subject, digest, now()));
    }

    private Instant now() {
        return clock.instant();
    }

    private static String releaseId(String capabilityId, String version) {
        return capabilityId + ":" + version;
    }
}
