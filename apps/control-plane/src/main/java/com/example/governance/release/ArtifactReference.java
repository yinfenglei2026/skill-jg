package com.example.governance.release;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ArtifactReference(String value, String registry, String repository, String digest) {
    private static final Pattern OCI_DIGEST_REFERENCE = Pattern.compile(
            "^oci://(?<registry>[A-Za-z0-9][A-Za-z0-9._:-]*)/(?<repository>[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*)@(?<digest>sha256:[a-f0-9]{64})$");

    public static ArtifactReference parse(String value) {
        Matcher matcher = OCI_DIGEST_REFERENCE.matcher(value);
        if (!matcher.matches()) {
            throw new InvalidArtifactReferenceException();
        }
        return new ArtifactReference(value, matcher.group("registry"), matcher.group("repository"), matcher.group("digest"));
    }

    public boolean matchesManifestUri(String manifestUri) {
        String repositoryUri = "oci://" + registry + "/" + repository;
        return repositoryUri.equals(manifestUri) || value.equals(manifestUri);
    }
}
