package com.example.governance.release;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class VerificationEvidence {
    @Column(name = "evidence_type", nullable = false, updatable = false, length = 64)
    private String type;

    @Column(name = "evidence_subject", nullable = false, updatable = false, length = 512)
    private String subject;

    @Column(name = "evidence_digest", nullable = false, updatable = false, length = 71)
    private String digest;

    protected VerificationEvidence() {
    }

    public VerificationEvidence(String type, String subject, String digest) {
        this.type = type;
        this.subject = subject;
        this.digest = digest;
    }

    public String type() {
        return type;
    }

    public String subject() {
        return subject;
    }

    public String digest() {
        return digest;
    }
}
