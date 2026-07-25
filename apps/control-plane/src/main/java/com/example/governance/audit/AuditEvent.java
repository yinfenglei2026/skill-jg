package com.example.governance.audit;

import java.time.Instant;

public record AuditEvent(String action, String subject, String digest, Instant occurredAt) {
}
