package com.example.governance.release;

public final class InvalidReleaseTransitionException extends RuntimeException {
    public InvalidReleaseTransitionException(ReleaseState current, ReleaseState requested) {
        super("Cannot transition release from " + current + " to " + requested);
    }
}
