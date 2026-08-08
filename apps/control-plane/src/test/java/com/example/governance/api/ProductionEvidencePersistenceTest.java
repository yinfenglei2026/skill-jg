package com.example.governance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.example.governance.manifest.CapabilityPackage;
import com.example.governance.manifest.CapabilityPackageParser;
import com.example.governance.release.ArtifactVerificationException;
import com.example.governance.release.ArtifactVerificationFailure;
import com.example.governance.release.ArtifactVerifier;
import com.example.governance.release.ReleaseRepository;
import com.example.governance.release.VerificationEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProductionEvidencePersistenceTest {
    private static final String DEPARTMENT = "customer-operations";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityPackageParser PARSER = new CapabilityPackageParser();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReleaseRepository releases;

    @MockBean
    private ArtifactVerifier verifier;

    @Test
    void persists_and_serializes_all_three_evidence_records_in_order() throws Exception {
        ManifestFixture fixture = manifest("production-evidence-agent");
        List<VerificationEvidence> evidence = List.of(
                new VerificationEvidence("REGISTRY_DIGEST", fixture.artifactReference(), fixture.digest()),
                new VerificationEvidence("COSIGN_SIGNATURE", "key-sha256:abc123", fixture.digest()),
                new VerificationEvidence("SLSA_PROVENANCE", "builder:https://builder.example/internal|source:"
                        + fixture.repository() + "@" + fixture.revision(), fixture.digest()));
        doReturn(evidence).when(verifier).verify(any());
        createCapability(fixture.id());

        register(fixture).andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidence[0].type").value("REGISTRY_DIGEST"))
                .andExpect(jsonPath("$.evidence[1].type").value("COSIGN_SIGNATURE"))
                .andExpect(jsonPath("$.evidence[2].type").value("SLSA_PROVENANCE"));

        mockMvc.perform(get("/api/v1/releases/{id}", fixture.id() + ":1.0.0")
                        .with(jwt().jwt(token -> token.subject("reader").claim("department", DEPARTMENT))
                                .authorities(new SimpleGrantedAuthority("ROLE_READ_ONLY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(3));
    }

    @Test
    void maps_every_failure_to_a_sanitized_422_without_persisting_a_release() throws Exception {
        int index = 0;
        for (ArtifactVerificationFailure failure : ArtifactVerificationFailure.values()) {
            reset(verifier);
            String id = "failed-agent-" + index++;
            ManifestFixture fixture = manifest(id);
            createCapability(id);
            doThrow(new ArtifactVerificationException(failure)).when(verifier).verify(any());

            register(fixture).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("UNVERIFIED_ARTIFACT"))
                    .andExpect(jsonPath("$.message").value(failure.publicMessage()));

            assertThat(releases.findById(id + ":1.0.0")).isEmpty();
        }
    }

    private void createCapability(String id) throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .with(jwt().jwt(token -> token.subject("owner").claim("department", DEPARTMENT))
                                .authorities(new SimpleGrantedAuthority("ROLE_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", id, "department", DEPARTMENT, "type", "AGENT"))))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions register(ManifestFixture fixture) throws Exception {
        return mockMvc.perform(post("/api/v1/capabilities/{id}/releases", fixture.id())
                .with(jwt().jwt(token -> token.subject("release-bot").claim("department", DEPARTMENT))
                        .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "version", "1.0.0",
                        "artifactReference", fixture.artifactReference(),
                        "manifest", fixture.document()))));
    }

    private ManifestFixture manifest(String id) {
        String repository = "https://git.example.internal/governance/" + id + ".git";
        String revision = "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42";
        String document = """
                apiVersion: governance.platform.example/v1alpha1
                kind: CapabilityPackage
                metadata:
                  name: %s-package
                  namespace: %s
                  version: 1.0.0
                release:
                  artifact:
                    uri: oci://registry.example.internal/governance/%s
                    mediaType: application/vnd.example.capability.bundle.v1+tar
                  source:
                    repository: %s
                    revision: %s
                spec:
                  capabilities:
                    - id: %s
                      type: Agent
                      entrypoint:
                        artifactPath: agents/%s
                        command: ["/opt/platform/bin/agent-runner"]
                        args: []
                      permissions:
                        modelPolicies: [general-chat]
                      dependencies:
                        capabilities: []
                        skills: []
                      network:
                        defaultDeny: true
                      secrets: []
                      resources:
                        requests: { cpu: 250m, memory: 512Mi }
                        limits: { cpu: "1", memory: 1Gi }
                      health:
                        startup: { httpGet: { path: /health/startup, port: 8080 } }
                        readiness: { httpGet: { path: /health/ready, port: 8080 } }
                        liveness: { httpGet: { path: /health/live, port: 8080 } }
                      runtimeProfile:
                        class: hosted-standard
                        isolation: namespace
                        replicas: 1
                        timeoutSeconds: 90
                        maxConcurrency: 8
                        terminationGracePeriodSeconds: 30
                """.formatted(id, DEPARTMENT, id, repository, revision, id, id);
        CapabilityPackage parsed = PARSER.parse(document);
        String artifactReference = "oci://registry.example.internal/governance/" + id + "@" + parsed.canonicalDigest();
        return new ManifestFixture(id, document, parsed.canonicalDigest(), artifactReference, repository, revision);
    }

    private record ManifestFixture(
            String id, String document, String digest, String artifactReference, String repository, String revision) {
    }
}
