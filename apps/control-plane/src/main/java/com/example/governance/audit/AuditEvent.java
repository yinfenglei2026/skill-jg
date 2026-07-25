package com.example.governance.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 256)
    private String actor;

    @Column(nullable = false, length = 128)
    private String department;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 256)
    private String subject;

    @Column(nullable = false, length = 32)
    private String decision;

    @Column(length = 71)
    private String digest;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(String actor, String department, String action, String subject, String decision,
                      String digest, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.department = department;
        this.action = action;
        this.subject = subject;
        this.decision = decision;
        this.digest = digest;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getDepartment() {
        return department;
    }

    public String getAction() {
        return action;
    }

    public String getSubject() {
        return subject;
    }

    public String getDecision() {
        return decision;
    }

    public String getDigest() {
        return digest;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
