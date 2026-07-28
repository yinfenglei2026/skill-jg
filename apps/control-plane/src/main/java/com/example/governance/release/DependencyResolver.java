package com.example.governance.release;

import com.example.governance.manifest.CapabilityPackage;
import com.example.governance.manifest.CapabilityPackage.DependencyDefinition;
import com.example.governance.manifest.InvalidCapabilityManifestException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DependencyResolver {
    private final ReleaseRepository releases;

    public DependencyResolver(ReleaseRepository releases) {
        this.releases = releases;
    }

    public List<ReleaseDependency> resolve(CapabilityPackage definition, String capabilityId) {
        List<ReleaseDependency> locks = definition.capability(capabilityId).dependencies().stream()
                .map(this::lock)
                .toList();
        assertAcyclic(capabilityId, locks, new LinkedHashSet<>());
        return locks;
    }

    private ReleaseDependency lock(DependencyDefinition dependency) {
        ReleaseDependency lock = new ReleaseDependency(
                dependency.id(), dependency.type(), dependency.version(), dependency.digest());
        if (!"SKILL".equals(dependency.type())) {
            requireMatchingRelease(lock);
        }
        return lock;
    }

    private void assertAcyclic(
            String capabilityId, List<ReleaseDependency> locks, Set<String> visiting) {
        if (!visiting.add(capabilityId)) {
            throw invalid("capability dependency cycle");
        }
        try {
            for (ReleaseDependency lock : locks) {
                if (!"SKILL".equals(lock.type())) {
                    Release dependency = requireMatchingRelease(lock);
                    assertAcyclic(lock.capabilityId(), dependency.dependencies(), visiting);
                }
            }
        } finally {
            visiting.remove(capabilityId);
        }
    }

    private Release requireMatchingRelease(ReleaseDependency dependency) {
        Release release = releases.findById(releaseId(dependency.capabilityId(), dependency.version()))
                .orElseThrow(() -> invalid("pinned capability dependency does not exist"));
        if (!release.manifestDigest().equals(dependency.digest())) {
            throw invalid("pinned capability dependency digest does not match");
        }
        return release;
    }

    private static String releaseId(String capabilityId, String version) {
        return capabilityId + ":" + version;
    }

    private static InvalidCapabilityManifestException invalid(String message) {
        return new InvalidCapabilityManifestException(message);
    }
}
