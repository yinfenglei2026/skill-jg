package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SlsaProvenancePolicyTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String REPOSITORY = "https://git.example.internal/team/app.git";
    private static final String REVISION = "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42";
    private static final String BUILDER = "https://builder.example/internal";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactVerificationRequest request = new ArtifactVerificationRequest(
            ArtifactReference.parse("oci://registry.example.internal/team/app@" + DIGEST), REPOSITORY, REVISION);

    @Test
    void accepts_one_matching_slsa_v1_statement_and_returns_bounded_summary() throws Exception {
        SlsaProvenanceSummary summary = policy().verify(request, attestation(BUILDER, REPOSITORY, REVISION));

        assertThat(summary.subject()).isEqualTo("builder:" + BUILDER + "|source:" + REPOSITORY + "@" + REVISION);
    }

    @Test
    void rejects_each_policy_mismatch_and_ambiguous_statement() throws Exception {
        assertFailure(attestation("https://other-builder.example", REPOSITORY, REVISION),
                ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH);
        assertFailure(attestation(BUILDER, "https://other.example/source.git", REVISION),
                ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH);
        assertFailure(attestation(BUILDER, REPOSITORY, "a".repeat(40)),
                ArtifactVerificationFailure.PROVENANCE_POLICY_MISMATCH);
        String duplicate = "[" + statement(BUILDER, REPOSITORY, REVISION) + ","
                + statement(BUILDER, REPOSITORY, REVISION) + "]";
        assertFailure(duplicate, ArtifactVerificationFailure.PROVENANCE_INVALID);
        assertFailure("[]", ArtifactVerificationFailure.PROVENANCE_MISSING);
    }

    private void assertFailure(String json, ArtifactVerificationFailure expected) {
        assertThatThrownBy(() -> policy().verify(request, json))
                .isInstanceOf(ArtifactVerificationException.class)
                .extracting(error -> ((ArtifactVerificationException) error).failure())
                .isEqualTo(expected);
    }

    private SlsaProvenancePolicy policy() {
        return new SlsaProvenancePolicy(Set.of(java.net.URI.create(BUILDER)));
    }

    private String attestation(String builder, String repository, String revision) throws Exception {
        return "[" + statement(builder, repository, revision) + "]";
    }

    private String statement(String builder, String repository, String revision) throws Exception {
        ObjectNode statement = mapper.createObjectNode();
        statement.put("_type", "https://in-toto.io/Statement/v1");
        ObjectNode subject = statement.putArray("subject").addObject();
        subject.put("name", request.artifact().value());
        subject.putObject("digest").put("sha256", DIGEST.substring("sha256:".length()));
        statement.put("predicateType", "https://slsa.dev/provenance/v1");
        ObjectNode predicate = statement.putObject("predicate");
        ObjectNode dependency = predicate.putObject("buildDefinition").putArray("resolvedDependencies")
                .addObject();
        dependency.put("uri", repository);
        dependency.putObject("digest").put("gitCommit", revision);
        predicate.putObject("runDetails").putObject("builder").put("id", builder);
        String payload = Base64.getEncoder().encodeToString(
                mapper.writeValueAsString(statement).getBytes(StandardCharsets.UTF_8));
        return mapper.createObjectNode().put("payload", payload).toString();
    }
}
