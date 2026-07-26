package com.example.governance.release;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "releases")
public class Release {
    @Id
    @Column(nullable = false, updatable = false, length = 256)
    private String id;

    @Column(name = "capability_id", nullable = false, updatable = false, length = 128)
    private String capabilityId;

    @Column(nullable = false, updatable = false, length = 64)
    private String version;

    @Column(name = "artifact_reference", nullable = false, updatable = false, length = 512)
    private String artifactReference;

    @Column(nullable = false, updatable = false, length = 71)
    private String digest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReleaseState state = ReleaseState.DRAFT;

    @Embedded
    private ReleaseApproval approval;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "release_transitions", joinColumns = @JoinColumn(name = "release_id"))
    @OrderColumn(name = "transition_order")
    private List<ReleaseTransition> transitions = new ArrayList<>();

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected Release() {
    }

    private Release(String capabilityId, String version, String artifactReference, String digest, Instant createdAt) {
        this.id = releaseId(capabilityId, version);
        this.capabilityId = capabilityId;
        this.version = version;
        this.artifactReference = artifactReference;
        this.digest = digest;
        this.createdAt = createdAt;
    }

    public static Release draft(String capabilityId, String version, String artifactReference, String digest, Instant createdAt) {
        return new Release(capabilityId, version, artifactReference, digest, createdAt);
    }

    public void validationPassed(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.DRAFT), ReleaseState.VALIDATING, actor, occurredAt);
    }

    public void reviewRequired(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.VALIDATING), ReleaseState.REVIEW_REQUIRED, actor, occurredAt);
    }

    public void approve(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.REVIEW_REQUIRED), ReleaseState.APPROVED, actor, occurredAt);
        approval = new ReleaseApproval(digest, actor, occurredAt);
    }

    public void publish(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.APPROVED), ReleaseState.PUBLISHED, actor, occurredAt);
    }

    public void deploying(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.PUBLISHED, ReleaseState.FAILED, ReleaseState.DEGRADED), ReleaseState.DEPLOYING, actor, occurredAt);
    }

    public void deployed(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.DEPLOYING), ReleaseState.DEPLOYED, actor, occurredAt);
    }

    public void degraded(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.DEPLOYED), ReleaseState.DEGRADED, actor, occurredAt);
    }

    public void failed(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.DEPLOYING, ReleaseState.DEGRADED), ReleaseState.FAILED, actor, occurredAt);
    }

    public void reject(String actor, Instant occurredAt) {
        transition(Set.of(ReleaseState.VALIDATING, ReleaseState.REVIEW_REQUIRED), ReleaseState.REJECTED, actor, occurredAt);
    }

    public void revoke(String actor, Instant occurredAt) {
        transition(EnumSet.of(ReleaseState.APPROVED, ReleaseState.PUBLISHED, ReleaseState.DEPLOYING,
                ReleaseState.DEPLOYED, ReleaseState.DEGRADED, ReleaseState.FAILED), ReleaseState.REVOKED, actor, occurredAt);
    }

    public ReleaseState state() {
        return state;
    }

    public String id() {
        return id;
    }

    public ReleaseApproval approval() {
        if (approval == null) {
            throw new IllegalStateException("Release has not been approved");
        }
        return approval;
    }

    public List<ReleaseTransition> transitions() {
        return List.copyOf(transitions);
    }

    public String capabilityId() {
        return capabilityId;
    }

    public String version() {
        return version;
    }

    public String digest() {
        return digest;
    }

    public String artifactReference() {
        return artifactReference;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private void transition(Set<ReleaseState> expected, ReleaseState target, String actor, Instant occurredAt) {
        if (!expected.contains(state)) {
            throw new InvalidReleaseTransitionException(state, target);
        }
        ReleaseState previous = state;
        state = target;
        transitions.add(new ReleaseTransition(previous, target, actor, occurredAt));
    }

    private static String releaseId(String capabilityId, String version) {
        return capabilityId + ":" + version;
    }
}
