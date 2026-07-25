package com.example.governance.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GovernanceApiTest {
    private static final String DIGEST = "sha256:0f" + "2".repeat(62);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishes_only_after_the_same_digest_is_approved_and_audits_each_mutation() throws Exception {
        String capability = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"support-agent","department":"customer-operations","type":"AGENT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("support-agent"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/v1/capabilities/support-agent/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","digest":"%s"}
                                """.formatted(DIGEST)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DRAFT"));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VALIDATING"));
        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/review-required"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"security-reviewer","digest":"%s"}
                                """.formatted(DIGEST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));

        mockMvc.perform(post("/api/v1/releases/support-agent:1.0.0/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[5].action").value("RELEASE_PUBLISHED"))
                .andExpect(jsonPath("$[5].digest").value(DIGEST));
    }

    @Test
    void rejects_an_approval_for_a_different_digest() throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"name\":\"records-mcp\",\"department\":\"customer-operations\",\"type\":\"MCP\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/capabilities/records-mcp/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"digest\":\"%s\"}".formatted(DIGEST)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/releases/records-mcp:1.0.0/validate"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/releases/records-mcp:1.0.0/review-required"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/releases/records-mcp:1.0.0/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"security-reviewer\",\"digest\":\"sha256:0f%s\"}".formatted("3".repeat(62))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
    }
}
