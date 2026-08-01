package com.example.governance.audit;

import com.example.governance.security.Actor;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditEventRepository auditEvents;

    public AuditService(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Actor actor, String action, String subject, String decision, String digest,
                       Instant occurredAt) {
        auditEvents.save(new AuditEvent(actor.subject(), actor.department(), action, subject, decision, digest,
                occurredAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeniedTransition(Actor actor, String releaseId, String digest, Instant occurredAt) {
        auditEvents.save(new AuditEvent(actor.subject(), actor.department(), "RELEASE_TRANSITION_DENIED", releaseId,
                "DENY", digest, occurredAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeniedApproval(Actor actor, String releaseId, String digest, Instant occurredAt) {
        auditEvents.save(new AuditEvent(actor.subject(), actor.department(), "RELEASE_APPROVAL_DENIED", releaseId,
                "DENY", digest, occurredAt));
    }
}
