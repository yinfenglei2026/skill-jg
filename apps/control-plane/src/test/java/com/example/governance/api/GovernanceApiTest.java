package com.example.governance.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
    private static final String DIGEST = "sha256:0f" + "2".repeat(62);
    private static final String ARTIFACT = "oci://registry.example.internal/governance/support-agent@" + DIGEST;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishes_only_after_role_guarded_transitions_and_audits_jwt_actor() throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .with(as("OWNER", "owner@example.internal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"support-agent","department":"customer-operations","type":"AGENT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("support-agent"));

        mockMvc.perform(post("/api/v1/capabilities/support-agent/releases")
                        .with(as("OPERATOR", "release-bot@example.internal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","artifactReference":"%s"}
                                """.formatted(ARTIFACT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.digest").value(DIGEST));

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
                .andExpect(jsonPath("$[5].digest").value(DIGEST));
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
    void rejects_malformed_or_mutable_artifact_references() throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .with(as("OWNER", "owner@example.internal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"name\":\"records-mcp\",\"department\":\"customer-operations\",\"type\":\"MCP\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/capabilities/records-mcp/releases")
                        .with(as("OPERATOR", "release-bot@example.internal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"artifactReference\":\"oci://registry.example.internal/governance/records-mcp:latest\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARTIFACT_REFERENCE"));
    }

    private static JwtRequestPostProcessor as(String role, String subject) {
        return jwt()
                .jwt(token -> token.subject(subject).claim("department", "customer-operations"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
