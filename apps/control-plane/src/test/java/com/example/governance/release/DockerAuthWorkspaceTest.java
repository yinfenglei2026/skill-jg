package com.example.governance.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DockerAuthWorkspaceTest {
    @Test
    void writes_operation_scoped_auth_and_removes_it_on_close() throws Exception {
        String password = "robot-secret";
        DockerAuthWorkspace workspace = DockerAuthWorkspaceFactory.create(
                "registry.example.internal", "robot$governance", password.toCharArray());
        java.nio.file.Path root = workspace.root();
        try (workspace) {
            JsonNode config = new ObjectMapper().readTree(Files.readString(workspace.configFile()));
            String encoded = config.path("auths").path("registry.example.internal").path("auth").textValue();
            assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                    .isEqualTo("robot$governance:" + password);
            assertThat(workspace.toString()).doesNotContain(password);
        }
        assertThat(Files.exists(root)).isFalse();
    }
}
