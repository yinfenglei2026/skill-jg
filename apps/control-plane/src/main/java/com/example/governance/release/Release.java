package com.example.governance.release;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Release {
    private final String capabilityId;
    private final String version;
    private final String digest;
    private final List<ReleaseTransition> transitions = new ArrayList<>();
    private ReleaseState state = ReleaseState.DRAFT;
    private ReleaseApproval approval;

    private Release(String capabilityId, String version, String digest) {
        this.capabilityId = capabilityId;
        this.version = version;
        this.digest = digest;
    }

    public static Release draft(String capabilityId, String version, String digest, Instant createdAt) {
        return new Release(capabilityId, version, digest);
    }

    public void validationPassed(Instant occurredAt) {
        transition(ReleaseState.DRAFT, ReleaseState.VALIDATING, occurredAt);
    }

    public void reviewRequired(Instant occurredAt) {
        transition(ReleaseState.VALIDATING, ReleaseState.REVIEW_REQUIRED, occurredAt);
    }

    public void approve(String actor, Instant occurredAt) {
        transition(ReleaseState.REVIEW_REQUIRED, ReleaseState.APPROVED, occurredAt);
        approval = new ReleaseApproval(digest, actor, occurredAt);
    }

    public void publish(Instant occurredAt) {
        transition(ReleaseState.APPROVED, ReleaseState.PUBLISHED, occurredAt);
    }

    public ReleaseState state() {
        return state;
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

    private void transition(ReleaseState expected, ReleaseState target, Instant occurredAt) {
        if (state != expected) {
            throw new InvalidReleaseTransitionException(state, target);
        }
        state = target;
        transitions.add(new ReleaseTransition(target, occurredAt));
    }
}
