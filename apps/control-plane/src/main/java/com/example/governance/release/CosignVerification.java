package com.example.governance.release;

public record CosignVerification(
        String publicKeyFingerprint,
        String signatureJson,
        String attestationJson) {
}
