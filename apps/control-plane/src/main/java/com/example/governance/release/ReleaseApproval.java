package com.example.governance.release;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

@Embeddable
public class ReleaseApproval {
    @Column(name = "approval_digest", length = 71)
    private String digest;

    @Column(name = "approval_actor", length = 256)
    private String actor;

    @Column(name = "approval_decided_at")
    private Instant decidedAt;

    protected ReleaseApproval() {
    }

    public ReleaseApproval(String digest, String actor, Instant decidedAt) {
        this.digest = digest;
        this.actor = actor;
        this.decidedAt = decidedAt;
    }

    public String digest() {
        return digest;
    }

    public String actor() {
        return actor;
    }

    public Instant decidedAt() {
        return decidedAt;
    }
}
