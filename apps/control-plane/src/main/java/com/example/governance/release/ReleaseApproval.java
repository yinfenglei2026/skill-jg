package com.example.governance.release;

import java.time.Instant;

public record ReleaseApproval(String digest, String actor, Instant decidedAt) {
}
