package com.example.governance.api;

public final class DigestMismatchException extends RuntimeException {
    public DigestMismatchException() {
        super("Approval digest does not match the release digest");
    }
}
