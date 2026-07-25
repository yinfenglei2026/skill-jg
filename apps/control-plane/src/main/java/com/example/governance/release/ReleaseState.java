package com.example.governance.release;

public enum ReleaseState {
    DRAFT,
    VALIDATING,
    REVIEW_REQUIRED,
    APPROVED,
    PUBLISHED,
    DEPLOYING,
    DEPLOYED,
    REJECTED,
    REVOKED,
    FAILED,
    DEGRADED
}
