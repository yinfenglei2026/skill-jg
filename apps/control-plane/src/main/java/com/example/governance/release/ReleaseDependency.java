package com.example.governance.release;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ReleaseDependency {
    @Column(name = "dependency_capability_id", nullable = false)
    private String capabilityId;

    @Column(name = "dependency_type", nullable = false)
    private String type;

    @Column(name = "dependency_version", nullable = false)
    private String version;

    @Column(name = "dependency_digest", nullable = false)
    private String digest;

    protected ReleaseDependency() {
    }

    public ReleaseDependency(String capabilityId, String type, String version, String digest) {
        this.capabilityId = capabilityId;
        this.type = type;
        this.version = version;
        this.digest = digest;
    }

    public String capabilityId() {
        return capabilityId;
    }

    public String type() {
        return type;
    }

    public String version() {
        return version;
    }

    public String digest() {
        return digest;
    }
}
