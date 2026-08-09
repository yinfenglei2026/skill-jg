package com.example.governance.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

public final class CosignClient {
    private final CommandRunner runner;
    private final ArtifactVerificationSettings settings;
    private final ObjectMapper json = new ObjectMapper();

    public CosignClient(CommandRunner runner, ArtifactVerificationSettings settings) {
        this.runner = runner;
        this.settings = settings;
    }

    public CosignVerification verify(ArtifactVerificationRequest request) {
        try (DockerAuthWorkspace workspace = DockerAuthWorkspaceFactory.create(
                settings.registry(), settings.username(), settings.password())) {
            Map<String, String> environment = Map.of("DOCKER_CONFIG", workspace.root().toString());
            CommandResult signature = runner.run(signatureCommand(request), environment, workspace.root(),
                    settings.cosignTimeout(), settings.maxVerifierOutputBytes());
            if (signature.exitCode() != 0) {
                throw failure(ArtifactVerificationFailure.SIGNATURE_INVALID);
            }
            requireNonEmptyArray(signature.stdout(), ArtifactVerificationFailure.SIGNATURE_INVALID);

            CommandResult attestation = runner.run(attestationCommand(request), environment, workspace.root(),
                    settings.cosignTimeout(), settings.maxVerifierOutputBytes());
            if (attestation.exitCode() != 0) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_INVALID);
            }
            String attestations = normalizeAttestationRecords(attestation.stdout());
            return new CosignVerification(publicKeyFingerprint(), signature.stdout(), attestations);
        }
    }

    private List<String> signatureCommand(ArtifactVerificationRequest request) {
        List<String> command = baseCommand("verify");
        command.add("--output");
        command.add("json");
        addCa(command);
        command.add(cosignReference(request.artifact()));
        return List.copyOf(command);
    }

    private List<String> attestationCommand(ArtifactVerificationRequest request) {
        List<String> command = baseCommand("verify-attestation");
        command.add("--type");
        command.add("slsaprovenance1");
        command.add("--output");
        command.add("json");
        addCa(command);
        command.add(cosignReference(request.artifact()));
        return List.copyOf(command);
    }

    private String cosignReference(ArtifactReference artifact) {
        return artifact.registry() + "/" + artifact.repository() + "@" + artifact.digest();
    }

    private List<String> baseCommand(String operation) {
        List<String> command = new ArrayList<>();
        command.add(settings.cosignExecutable().toString());
        command.add(operation);
        command.add("--key");
        command.add(settings.cosignPublicKey().toString());
        return command;
    }

    private void addCa(List<String> command) {
        if (settings.registryCaCert() != null) {
            command.add("--registry-cacert");
            command.add(settings.registryCaCert().toString());
        }
    }

    private void requireNonEmptyArray(String output, ArtifactVerificationFailure emptyFailure) {
        try {
            JsonNode root = json.readTree(output);
            if (root == null || !root.isArray()) {
                throw failure(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
            }
            if (root.isEmpty()) {
                throw failure(emptyFailure);
            }
        } catch (ArtifactVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
        }
    }

    private String normalizeAttestationRecords(String output) {
        try (JsonParser parser = json.createParser(output)) {
            ArrayNode records = json.createArrayNode();
            JsonNode value;
            while ((value = parser.readValueAsTree()) != null) {
                if (value.isArray()) {
                    value.forEach(records::add);
                } else if (value.isObject()) {
                    records.add(value);
                } else {
                    throw failure(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
                }
            }
            if (records.isEmpty()) {
                throw failure(ArtifactVerificationFailure.PROVENANCE_MISSING);
            }
            return json.writeValueAsString(records);
        } catch (ArtifactVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(ArtifactVerificationFailure.VERIFIER_OUTPUT_INVALID);
        }
    }

    private String publicKeyFingerprint() {
        try {
            String pem = Files.readString(settings.cosignPublicKey(), StandardCharsets.US_ASCII);
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (Exception exception) {
            throw failure(ArtifactVerificationFailure.COSIGN_UNAVAILABLE);
        }
    }

    private ArtifactVerificationException failure(ArtifactVerificationFailure failure) {
        return new ArtifactVerificationException(failure);
    }
}
