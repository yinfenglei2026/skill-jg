package com.example.governance.deployment;

import com.example.governance.release.Release;
import com.example.governance.security.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_intents")
public class DeploymentIntent {
    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "release_id", nullable = false, updatable = false, unique = true, length = 256)
    private String releaseId;

    @Column(nullable = false, updatable = false, length = 128)
    private String department;

    @Column(name = "desired_digest", nullable = false, updatable = false, length = 71)
    private String desiredDigest;

    @Column(name = "observed_digest", length = 71)
    private String observedDigest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeploymentStatus status;

    @Column(name = "requested_actor", nullable = false, updatable = false, length = 256)
    private String requestedActor;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected DeploymentIntent() {
    }

    private DeploymentIntent(Release release, Actor actor, Instant requestedAt) {
        this.id = UUID.randomUUID().toString();
        this.releaseId = release.id();
        this.department = actor.department();
        this.desiredDigest = release.digest();
        this.status = DeploymentStatus.PENDING;
        this.requestedActor = actor.subject();
        this.requestedAt = requestedAt;
    }

    public static DeploymentIntent pending(Release release, Actor actor, Instant requestedAt) {
        return new DeploymentIntent(release, actor, requestedAt);
    }

    public void reconciling() {
        status = DeploymentStatus.RECONCILING;
    }

    public void observe(RuntimeObservation observation, Instant occurredAt) {
        observedDigest = observation.digest();
        status = observation.status() == DeploymentStatus.READY && !desiredDigest.equals(observation.digest())
                ? DeploymentStatus.DRIFTED
                : observation.status();
        observedAt = occurredAt;
    }

    public String id() {
        return id;
    }

    public String releaseId() {
        return releaseId;
    }

    public String department() {
        return department;
    }

    public String desiredDigest() {
        return desiredDigest;
    }

    public String observedDigest() {
        return observedDigest;
    }

    public DeploymentStatus status() {
        return status;
    }

    public String requestedActor() {
        return requestedActor;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant observedAt() {
        return observedAt;
    }
}
