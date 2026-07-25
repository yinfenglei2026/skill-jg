package com.example.governance.release;

import java.time.Instant;

public record ReleaseTransition(ReleaseState to, Instant occurredAt) {
}
