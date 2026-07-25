package com.example.governance.release;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ArtifactReference(String value, String digest) {
    private static final Pattern OCI_DIGEST_REFERENCE = Pattern.compile(
            "^oci://[A-Za-z0-9][A-Za-z0-9._:-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)+@(sha256:[a-f0-9]{64})$");

    public static ArtifactReference parse(String value) {
        Matcher matcher = OCI_DIGEST_REFERENCE.matcher(value);
        if (!matcher.matches()) {
            throw new InvalidArtifactReferenceException();
        }
        return new ArtifactReference(value, matcher.group(1));
    }
}
