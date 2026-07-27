package com.example.governance.manifest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import com.example.governance.manifest.CapabilityPackage.CapabilityDefinition;
import com.example.governance.manifest.CapabilityPackage.DependencyDefinition;
import com.example.governance.manifest.CapabilityPackage.Metadata;
import com.example.governance.manifest.CapabilityPackage.NetworkDefinition;
import com.example.governance.manifest.CapabilityPackage.ReleaseDescriptor;
import com.example.governance.manifest.CapabilityPackage.SecretDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public final class CapabilityPackageParser {
    private static final String API_VERSION = "governance.platform.example/v1alpha1";
    private static final String KIND = "CapabilityPackage";
    private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public CapabilityPackage parse(String document) {
        ObjectNode root = readRoot(document);
        String apiVersion = text(root, "apiVersion", "apiVersion");
        if (!API_VERSION.equals(apiVersion)) {
            throw invalid("unsupported apiVersion");
        }
        String kind = text(root, "kind", "kind");
        if (!KIND.equals(kind)) {
            throw invalid("unsupported kind");
        }

        ObjectNode metadataNode = object(root, "metadata", "metadata");
        Metadata metadata = new Metadata(
                text(metadataNode, "name", "metadata.name"),
                text(metadataNode, "namespace", "metadata.namespace"),
                text(metadataNode, "version", "metadata.version"));

        ObjectNode releaseNode = object(root, "release", "release");
        ReleaseDescriptor release = new ReleaseDescriptor(optionalText(releaseNode, "digest"));

        ObjectNode specNode = object(root, "spec", "spec");
        ArrayNode capabilityNodes = array(specNode, "capabilities", "spec.capabilities");
        if (capabilityNodes.isEmpty()) {
            throw invalid("missing required spec.capabilities");
        }

        List<CapabilityDefinition> capabilities = new ArrayList<>();
        for (JsonNode capabilityNode : capabilityNodes) {
            capabilities.add(parseCapability(requireObject(capabilityNode, "spec.capabilities entry")));
        }

        CanonicalForm canonical = canonicalize(root);
        return new CapabilityPackage(apiVersion, kind, metadata, release, capabilities,
                canonical.document(), canonical.digest());
    }

    private CapabilityDefinition parseCapability(ObjectNode node) {
        String id = text(node, "id", "capability.id");
        String type = text(node, "type", "capability.type");
        ObjectNode dependencyGroups = object(node, "dependencies", "capability.dependencies");
        List<DependencyDefinition> dependencies = new ArrayList<>();
        addDependencies(dependencies, dependencyGroups, "capabilities");
        addDependencies(dependencies, dependencyGroups, "skills");

        ObjectNode networkNode = object(node, "network", "capability.network");
        JsonNode defaultDeny = networkNode.get("defaultDeny");
        if (defaultDeny == null || !defaultDeny.isBoolean() || !defaultDeny.booleanValue()) {
            throw invalid("network.defaultDeny must be true");
        }

        List<SecretDefinition> secrets = parseSecrets(node);
        return new CapabilityDefinition(id, type, dependencies, new NetworkDefinition(true), secrets);
    }

    private void addDependencies(List<DependencyDefinition> dependencies, ObjectNode groups, String field) {
        ArrayNode entries = array(groups, field, "capability.dependencies." + field);
        for (JsonNode entryNode : entries) {
            ObjectNode entry = requireObject(entryNode, "dependency");
            String digest = optionalText(entry, "digest");
            if (digest == null || !SHA256.matcher(digest).matches()) {
                throw invalid("dependency digest must be a sha256 digest");
            }
            dependencies.add(new DependencyDefinition(
                    text(entry, "id", "dependency.id"),
                    text(entry, "type", "dependency.type"),
                    text(entry, "version", "dependency.version"),
                    digest,
                    optionalText(entry, "importPath")));
        }
    }

    private List<SecretDefinition> parseSecrets(ObjectNode capability) {
        ArrayNode secretNodes = array(capability, "secrets", "capability.secrets");
        List<SecretDefinition> secrets = new ArrayList<>();
        for (JsonNode secretNode : secretNodes) {
            ObjectNode secret = requireObject(secretNode, "secret");
            if (secret.has("value")) {
                throw invalid("inline secret values are forbidden");
            }
            ObjectNode reference = object(secret, "ref", "secret.ref");
            secrets.add(new SecretDefinition(
                    text(secret, "name", "secret.name"),
                    text(reference, "provider", "secret.ref.provider"),
                    text(reference, "key", "secret.ref.key"),
                    text(reference, "version", "secret.ref.version")));
        }
        return secrets;
    }

    private CanonicalForm canonicalize(ObjectNode root) {
        ObjectNode canonicalRoot = root.deepCopy();
        JsonNode release = canonicalRoot.get("release");
        if (release instanceof ObjectNode releaseObject) {
            releaseObject.remove("digest");
        }
        Object genericDocument = canonicalMapper.convertValue(canonicalRoot, Object.class);
        try {
            String canonicalDocument = canonicalMapper.writeValueAsString(genericDocument);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            String digest = HexFormat.of().formatHex(
                    messageDigest.digest(canonicalDocument.getBytes(StandardCharsets.UTF_8)));
            return new CanonicalForm(canonicalDocument, "sha256:" + digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private ObjectNode readRoot(String document) {
        try {
            JsonNode root = yamlMapper.readTree(document);
            return requireObject(root, "document");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private ObjectNode object(ObjectNode parent, String field, String path) {
        return requireObject(parent.get(field), path);
    }

    private ObjectNode requireObject(JsonNode node, String path) {
        if (!(node instanceof ObjectNode objectNode)) {
            throw invalid("missing required " + path);
        }
        return objectNode;
    }

    private ArrayNode array(ObjectNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (!(node instanceof ArrayNode arrayNode)) {
            throw invalid("missing required " + path);
        }
        return arrayNode;
    }

    private String text(ObjectNode parent, String field, String path) {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank()) {
            throw invalid("missing required " + path);
        }
        return value;
    }

    private String optionalText(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    private InvalidCapabilityManifestException invalid(String message) {
        return new InvalidCapabilityManifestException(message);
    }

    private record CanonicalForm(String document, String digest) {
    }
}
