package com.example.governance.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class DockerAuthWorkspaceFactory {
    private DockerAuthWorkspaceFactory() {
    }

    public static DockerAuthWorkspace create(String registry, String username, char[] password) {
        if (registry == null || registry.isBlank() || username == null || username.isBlank()
                || password == null || password.length == 0) {
            throw new IllegalArgumentException("Docker auth settings must not be blank");
        }
        try {
            Path root = Files.createTempDirectory("cosign-auth-");
            Path configFile = root.resolve("config.json");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode config = mapper.createObjectNode();
            ObjectNode auths = config.putObject("auths");
            String credentials = username + ":" + new String(password);
            auths.putObject(registry).put("auth", Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(configFile, mapper.writeValueAsString(config), StandardCharsets.UTF_8);
            return new DockerAuthWorkspace(root, configFile, password);
        } catch (Exception exception) {
            throw new IllegalStateException("could not create Docker auth workspace", exception);
        }
    }
}
