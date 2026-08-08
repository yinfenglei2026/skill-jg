package com.example.governance.release;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SlsaProvenancePolicy {
    private static final String SLSA_V1 = "https://slsa.dev/provenance/v1";
    private final Set<URI> allowedBuilderIds;
    private final ObjectMapper json = new ObjectMapper();

    public SlsaProvenancePolicy(Set<URI> allowedBuilderIds) {
        this.allowedBuilderIds = Set.copyOf(allowedBuilderIds);
    }

    public SlsaProvenanceSummary verify(ArtifactVerificationRequest request, String attestationJson) {
        try {
            JsonNode envelopes = json.readTree(attestationJson);
            if (envelopes == null || !envelopes.isArray()) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
            }
            if (envelopes.isEmpty()) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_MISSING);
            }
            if (envelopes.size() != 1) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
            }
            JsonNode payload = envelopes.get(0).get("payload");
            if (payload == null || !payload.isTextual() || payload.textValue().isBlank()) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
            }
            JsonNode statement = json.readTree(new String(
                    Base64.getDecoder().decode(payload.textValue()), StandardCharsets.UTF_8));
            return validateStatement(request, statement);
        } catch (ArtifactVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
    }

    private SlsaProvenanceSummary validateStatement(ArtifactVerificationRequest request, JsonNode statement) {
        if (statement == null || !statement.isObject()) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        if (!SLSA_V1.equals(text(statement, "predicateType"))) {
            throw mismatch();
        }
        JsonNode subjects = statement.get("subject");
        if (subjects == null || !subjects.isArray() || subjects.size() != 1) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        String digest = text(subjects.get(0).path("digest"), "sha256");
        if (!request.artifact().digest().substring("sha256:".length()).equals(digest)) {
            throw mismatch();
        }
        JsonNode predicate = statement.get("predicate");
        if (predicate == null || !predicate.isObject()) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        String builderText = text(predicate.path("runDetails").path("builder"), "id");
        URI builder;
        try {
            builder = URI.create(builderText);
        } catch (IllegalArgumentException exception) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        if (!allowedBuilderIds.contains(builder)) {
            throw mismatch();
        }
        JsonNode dependencies = predicate.path("buildDefinition").get("resolvedDependencies");
        if (dependencies == null || !dependencies.isArray() || dependencies.size() != 1) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        JsonNode dependency = dependencies.get(0);
        if (!request.sourceRepository().equals(text(dependency, "uri"))
                || !request.sourceRevision().equals(text(dependency.path("digest"), "gitCommit"))) {
            throw mismatch();
        }
        SlsaProvenanceSummary summary = new SlsaProvenanceSummary(
                builder, request.sourceRepository(), request.sourceRevision());
        summary.subject();
        return summary;
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
        }
        return value.textValue();
    }

    private ArtifactVerificationException mismatch() {
        return failure(ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH);
    }

    private ArtifactVerificationException failure(ArtifactVerificationFailure failure) {
        return new ArtifactVerificationException(failure);
    }
}
