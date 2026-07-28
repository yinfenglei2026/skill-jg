package com.example.governance.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.governance.manifest.CapabilityPackage;
import com.example.governance.manifest.CapabilityPackageParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GovernanceApiTest {
    private static final String DEPARTMENT = "customer-operations";
    private static final String WRONG_DIGEST = "sha256:" + "f".repeat(64);
    private static final String SKILL_DIGEST = "sha256:" + "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityPackageParser MANIFEST_PARSER = new CapabilityPackageParser();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishes_only_after_role_guarded_transitions_and_audits_jwt_actor() throws Exception {
        createCapability("support-agent", "AGENT");
        ManifestFixture manifest = manifest("support-agent", "Agent", "1.0.0", List.of());

        registerRelease("support-agent", "1.0.0", manifest)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.digest").value(manifest.digest()));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/validate")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VALIDATING"));
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/review-required")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/approve")
                        .with(as("APPROVER", "approver@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/publish")
                        .with(as("OPERATOR", "release-bot@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/audit-events")
                        .with(as("READ_ONLY", "auditor@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[4].action").value("RELEASE_APPROVED"))
                .andExpect(jsonPath("$[4].actor").value("approver@example.internal"))
                .andExpect(jsonPath("$[5].digest").value(manifest.digest()));
    }

    @Test
    void persists_manifest_dependency_locks_and_test_attestation() throws Exception {
        createCapability("records-mcp", "MCP");
        createCapability("support-agent", "AGENT");
        ManifestFixture mcp = manifest("records-mcp", "MCP", "2.3.1", List.of());
        registerRelease("records-mcp", "2.3.1", mcp).andExpect(status().isCreated());

        ManifestFixture agent = manifest("support-agent", "Agent", "1.0.0", List.of(
                new DependencySpec("records-mcp", "MCP", "2.3.1", mcp.digest()),
                new DependencySpec("ticket-triage", "SKILL", "3.2.0", SKILL_DIGEST,
                        "skills/ticket-triage/SKILL.md")));

        registerRelease("support-agent", "1.0.0", agent)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manifestDigest").value(agent.digest()))
                .andExpect(jsonPath("$.dependencies", hasSize(2)))
                .andExpect(jsonPath("$.dependencies[0].capabilityId").value("records-mcp"))
                .andExpect(jsonPath("$.dependencies[0].type").value("MCP"))
                .andExpect(jsonPath("$.dependencies[0].version").value("2.3.1"))
                .andExpect(jsonPath("$.dependencies[0].digest").value(mcp.digest()))
                .andExpect(jsonPath("$.dependencies[0].importPath").doesNotExist())
                .andExpect(jsonPath("$.dependencies[1].capabilityId").value("ticket-triage"))
                .andExpect(jsonPath("$.dependencies[1].type").value("SKILL"))
                .andExpect(jsonPath("$.dependencies[1].version").value("3.2.0"))
                .andExpect(jsonPath("$.dependencies[1].digest").value(SKILL_DIGEST))
                .andExpect(jsonPath("$.dependencies[1].importPath").doesNotExist())
                .andExpect(jsonPath("$.evidence", hasSize(1)))
                .andExpect(jsonPath("$.evidence[0].type").value("TEST_ATTESTATION"))
                .andExpect(jsonPath("$.evidence[0].subject").value(agent.artifactReference()))
                .andExpect(jsonPath("$.evidence[0].digest").value(agent.digest()));
    }

    @Test
    void rejects_a_missing_pinned_dependency() throws Exception {
        createCapability("support-agent", "AGENT");
        ManifestFixture agent = manifest("support-agent", "Agent", "1.0.0", List.of(
                new DependencySpec("records-mcp", "MCP", "2.3.1", WRONG_DIGEST)));

        registerRelease("support-agent", "1.0.0", agent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPABILITY_MANIFEST"));
    }

    @Test
    void rejects_a_dependency_digest_that_does_not_match_the_stored_manifest() throws Exception {
        createCapability("records-mcp", "MCP");
        createCapability("support-agent", "AGENT");
        ManifestFixture mcp = manifest("records-mcp", "MCP", "2.3.1", List.of());
        registerRelease("records-mcp", "2.3.1", mcp).andExpect(status().isCreated());
        ManifestFixture agent = manifest("support-agent", "Agent", "1.0.0", List.of(
                new DependencySpec("records-mcp", "MCP", "2.3.1", WRONG_DIGEST)));

        registerRelease("support-agent", "1.0.0", agent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPABILITY_MANIFEST"));
    }

    @Test
    void rejects_a_capability_id_cycle_across_release_versions() throws Exception {
        createCapability("support-agent", "AGENT");
        createCapability("records-mcp", "MCP");
        ManifestFixture agent09 = manifest("support-agent", "Agent", "0.9.0", List.of());
        registerRelease("support-agent", "0.9.0", agent09).andExpect(status().isCreated());
        ManifestFixture mcp = manifest("records-mcp", "MCP", "1.0.0", List.of(
                new DependencySpec("support-agent", "Agent", "0.9.0", agent09.digest())));
        registerRelease("records-mcp", "1.0.0", mcp).andExpect(status().isCreated());
        ManifestFixture agent10 = manifest("support-agent", "Agent", "1.0.0", List.of(
                new DependencySpec("records-mcp", "MCP", "1.0.0", mcp.digest())));

        registerRelease("support-agent", "1.0.0", agent10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPABILITY_MANIFEST"));
    }

    @Test
    void rejects_unauthenticated_and_wrong_role_approval_requests() throws Exception {
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/approve"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/approve")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejects_unauthenticated_and_wrong_role_deployment_requests() throws Exception {
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deployments"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deployments")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejects_malformed_or_mutable_artifact_references() throws Exception {
        createCapability("records-mcp", "MCP");
        ManifestFixture manifest = manifest("records-mcp", "MCP", "1.0.0", List.of());

        registerRelease("records-mcp", "1.0.0", manifest,
                        "oci://registry.example.internal/governance/records-mcp:latest")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARTIFACT_REFERENCE"));
    }

    @Test
    void rejects_a_role_holder_from_a_different_department() throws Exception {
        createCapability("cross-department-agent", "AGENT");
        ManifestFixture manifest = manifest("cross-department-agent", "Agent", "1.0.0", List.of());

        mockMvc.perform(post("/api/v1/capabilities/cross-department-agent/releases")
                        .with(as("OPERATOR", "operator@example.internal", "finance"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseRequest("1.0.0", manifest.artifactReference(), manifest.document())))
                .andExpect(status().isForbidden());
    }

    @Test
    void lists_and_gets_catalog_records_only_within_the_jwt_department() throws Exception {
        createCapability("catalog-agent", "AGENT");
        ManifestFixture manifest = manifest("catalog-agent", "Agent", "1.0.0", List.of());
        registerRelease("catalog-agent", "1.0.0", manifest).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capabilities")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("catalog-agent"));
        mockMvc.perform(get("/api/v1/capabilities/catalog-agent")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value(DEPARTMENT));
        mockMvc.perform(get("/api/v1/capabilities/catalog-agent/releases")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("catalog-agent:1.0.0"))
                .andExpect(jsonPath("$[0].state").value("DRAFT"));
        mockMvc.perform(get("/api/v1/releases/catalog-agent:1.0.0")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("catalog-agent:1.0.0"));

        mockMvc.perform(get("/api/v1/capabilities")
                        .with(as("READ_ONLY", "finance-reader@example.internal", "finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/capabilities/catalog-agent")
                        .with(as("READ_ONLY", "finance-reader@example.internal", "finance")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/releases/catalog-agent:1.0.0")
                        .with(as("READ_ONLY", "finance-reader@example.internal", "finance")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciles_a_published_release_to_a_ready_deployment() throws Exception {
        ManifestFixture published = createPublishedRelease();

        String digest = published.digest();
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deployments")
                        .with(as("OPERATOR", "operator@example.internal")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releaseId").value("support-agent:1.0.0"))
                .andExpect(jsonPath("$.digest").value(digest))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.observedDigest").value(digest));

        mockMvc.perform(get("/api/v1/releases/support-agent:1.0.0/deployment")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value("support-agent:1.0.0"))
                .andExpect(jsonPath("$.observedDigest").value(digest))
                .andExpect(jsonPath("$.status").value("READY"));

        mockMvc.perform(get("/api/v1/releases/support-agent:1.0.0")
                        .with(as("READ_ONLY", "reader@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DEPLOYED"));

        mockMvc.perform(get("/api/v1/releases/support-agent:1.0.0/deployment")
                        .with(as("READ_ONLY", "finance-reader@example.internal", "finance")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/audit-events")
                        .with(as("READ_ONLY", "auditor@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'DEPLOYMENT_REQUESTED')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.action == 'RELEASE_DEPLOYING')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.action == 'RELEASE_DEPLOYED')]", hasSize(1)));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deploying")
                        .with(as("OPERATOR", "operator@example.internal")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejects_deployment_of_draft_unapproved_and_revoked_releases() throws Exception {
        createCapability("draft-agent", "AGENT");
        ManifestFixture draft = manifest("draft-agent", "Agent", "1.0.0", List.of());
        registerRelease("draft-agent", "1.0.0", draft).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/releases/draft-agent:1.0.0/deployments")
                        .with(as("OPERATOR", "operator@example.internal")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RELEASE_TRANSITION"));

        createCapability("unapproved-agent", "AGENT");
        ManifestFixture unapproved = manifest("unapproved-agent", "Agent", "1.0.0", List.of());
        registerRelease("unapproved-agent", "1.0.0", unapproved).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/releases/unapproved-agent:1.0.0/validate")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/unapproved-agent:1.0.0/review-required")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/unapproved-agent:1.0.0/deployments")
                        .with(as("OPERATOR", "operator@example.internal")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RELEASE_TRANSITION"));

        createPublishedRelease();

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/revoke")
                        .with(as("OPERATOR", "runtime-controller@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVOKED"));
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/deployments")
                        .with(as("OPERATOR", "operator@example.internal")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RELEASE_TRANSITION"));

        mockMvc.perform(get("/api/v1/audit-events")
                        .with(as("READ_ONLY", "auditor@example.internal")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'RELEASE_TRANSITION_DENIED')]", hasSize(3)))
                .andExpect(jsonPath("$[?(@.decision == 'DENY')]", hasSize(3)));
    }

    @Test
    void allows_the_local_portal_origin_for_api_preflight_requests() throws Exception {
        mockMvc.perform(options("/api/v1/capabilities")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
    }

    @Test
    void rejects_an_untrusted_portal_origin_for_api_preflight_requests() throws Exception {
        mockMvc.perform(options("/api/v1/capabilities")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private ManifestFixture createPublishedRelease() throws Exception {
        createCapability("support-agent", "AGENT");
        ManifestFixture manifest = manifest("support-agent", "Agent", "1.0.0", List.of());
        registerRelease("support-agent", "1.0.0", manifest).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/validate")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/review-required")
                        .with(as("REVIEWER", "reviewer@example.internal")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/approve")
                        .with(as("APPROVER", "approver@example.internal")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/publish")
                        .with(as("OPERATOR", "release-bot@example.internal")))
                .andExpect(status().isOk());
        return manifest;
    }

    private void createCapability(String id, String type) throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .with(as("OWNER", "owner@example.internal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", id,
                                "department", DEPARTMENT,
                                "type", type))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
    }

    private org.springframework.test.web.servlet.ResultActions registerRelease(
            String capabilityId, String version, ManifestFixture manifest) throws Exception {
        return registerRelease(capabilityId, version, manifest, manifest.artifactReference());
    }

    private org.springframework.test.web.servlet.ResultActions registerRelease(
            String capabilityId, String version, ManifestFixture manifest, String artifactReference) throws Exception {
        return mockMvc.perform(post("/api/v1/capabilities/{capabilityId}/releases", capabilityId)
                .with(as("OPERATOR", "release-bot@example.internal"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(releaseRequest(version, artifactReference, manifest.document())));
    }

    private static String releaseRequest(String version, String artifactReference, String manifest)
            throws JsonProcessingException {
        return JSON.writeValueAsString(Map.of(
                "version", version,
                "artifactReference", artifactReference,
                "manifest", manifest));
    }

    private static ManifestFixture manifest(
            String capabilityId, String type, String version, List<DependencySpec> dependencies) {
        List<Map<String, String>> dependencyDocuments = dependencies.stream()
                .filter(dependency -> !dependency.type().equals("SKILL"))
                .map(dependency -> Map.of(
                        "id", dependency.capabilityId(),
                        "type", dependency.type(),
                        "version", dependency.version(),
                        "digest", dependency.digest()))
                .toList();
        List<Map<String, String>> skillDocuments = dependencies.stream()
                .filter(dependency -> dependency.type().equals("SKILL"))
                .map(dependency -> Map.of(
                        "name", dependency.capabilityId(),
                        "version", dependency.version(),
                        "digest", dependency.digest(),
                        "importPath", dependency.importPath()))
                .toList();
        Map<String, Object> entrypoint = type.equals("Agent")
                ? Map.of("artifactPath", "agents/" + capabilityId,
                        "command", List.of("/opt/platform/bin/agent-runner"), "args", List.of())
                : Map.of("image", "registry.example.internal/mcp/" + capabilityId + "@" + WRONG_DIGEST,
                        "transport", Map.of("type", "streamable-http", "port", 8080, "path", "/mcp"));
        Map<String, Object> capability = Map.ofEntries(
                Map.entry("id", capabilityId),
                Map.entry("type", type),
                Map.entry("entrypoint", entrypoint),
                Map.entry("permissions", Map.of(
                        "modelPolicies", type.equals("Agent") ? List.of("general-chat") : List.of())),
                Map.entry("dependencies", Map.of(
                        "capabilities", dependencyDocuments,
                        "skills", skillDocuments)),
                Map.entry("network", Map.of("defaultDeny", true)),
                Map.entry("secrets", List.of()),
                Map.entry("resources", Map.of(
                        "requests", Map.of("cpu", "250m", "memory", "512Mi"),
                        "limits", Map.of("cpu", "1", "memory", "1Gi"))),
                Map.entry("health", Map.of(
                        "startup", probe("/health/startup"),
                        "readiness", probe("/health/ready"),
                        "liveness", probe("/health/live"))),
                Map.entry("runtimeProfile", Map.of(
                        "class", "hosted-standard",
                        "isolation", "namespace",
                        "replicas", 1,
                        "timeoutSeconds", 90,
                        "maxConcurrency", 8,
                        "terminationGracePeriodSeconds", 30)));
        try {
            String document = JSON.writeValueAsString(Map.of(
                    "apiVersion", "governance.platform.example/v1alpha1",
                    "kind", "CapabilityPackage",
                    "metadata", Map.of(
                            "name", capabilityId + "-package",
                            "namespace", DEPARTMENT,
                            "version", version),
                    "release", Map.of("artifact", Map.of(
                            "uri", "oci://registry.example.internal/governance/" + capabilityId,
                            "mediaType", "application/vnd.example.capability.bundle.v1+tar")),
                    "spec", Map.of("capabilities", List.of(capability))));
            CapabilityPackage parsed = MANIFEST_PARSER.parse(document);
            String digest = parsed.canonicalDigest();
            String artifactReference = "oci://registry.example.internal/governance/" + capabilityId + "@" + digest;
            return new ManifestFixture(document, digest, artifactReference);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> probe(String path) {
        return Map.of("httpGet", Map.of("path", path, "port", 8080));
    }

    private static JwtRequestPostProcessor as(String role, String subject) {
        return as(role, subject, DEPARTMENT);
    }

    private static JwtRequestPostProcessor as(String role, String subject, String department) {
        return jwt()
                .jwt(token -> token.subject(subject).claim("department", department))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private record ManifestFixture(String document, String digest, String artifactReference) {
    }

    private record DependencySpec(
            String capabilityId, String type, String version, String digest, String importPath) {
        private DependencySpec(String capabilityId, String type, String version, String digest) {
            this(capabilityId, type, version, digest, null);
        }
    }
}
