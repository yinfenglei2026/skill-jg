package com.example.governance.release;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

@Embeddable
public class ReleaseTransition {
    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, length = 32)
    private ReleaseState from;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 32)
    private ReleaseState to;

    @Column(nullable = false, length = 256)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ReleaseTransition() {
    }

    public ReleaseTransition(ReleaseState from, ReleaseState to, String actor, Instant occurredAt) {
        this.from = from;
        this.to = to;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public ReleaseState from() {
        return from;
    }

    public ReleaseState to() {
        return to;
    }

    public String actor() {
        return actor;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
