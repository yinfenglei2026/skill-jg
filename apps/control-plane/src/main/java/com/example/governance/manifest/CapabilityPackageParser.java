package com.example.governance.manifest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

public final class CapabilityPackageParser {
    private static final String API_VERSION = "governance.platform.example/v1alpha1";
    private static final String KIND = "CapabilityPackage";
    private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> ROOT_FIELDS = Set.of("apiVersion", "kind", "metadata", "release", "spec");
    private static final Set<String> METADATA_FIELDS = Set.of("name", "namespace", "version", "labels");
    private static final Set<String> RELEASE_FIELDS = Set.of("digest", "artifact", "source");
    private static final Set<String> ARTIFACT_FIELDS = Set.of("uri", "mediaType");
    private static final Set<String> SOURCE_FIELDS = Set.of("repository", "revision");
    private static final Set<String> SPEC_FIELDS = Set.of("capabilities");
    private static final Set<String> CAPABILITY_FIELDS = Set.of(
            "id", "type", "entrypoint", "dependencies", "permissions", "network", "secrets",
            "resources", "health", "runtimeProfile");
    private static final Set<String> DEPENDENCY_GROUP_FIELDS = Set.of("capabilities", "skills");
    private static final Set<String> CAPABILITY_DEPENDENCY_FIELDS = Set.of(
            "id", "type", "version", "digest", "required");
    private static final Set<String> SKILL_DEPENDENCY_FIELDS = Set.of(
            "name", "version", "digest", "importPath");
    private static final Set<String> NETWORK_FIELDS = Set.of("defaultDeny", "allow");
    private static final Set<String> NETWORK_ALLOW_FIELDS = Set.of("name", "protocol", "host", "port");
    private static final Set<String> SECRET_FIELDS = Set.of("name", "ref", "mount");
    private static final Set<String> SECRET_REF_FIELDS = Set.of("provider", "key", "version");
    private static final Set<String> SECRET_MOUNT_FIELDS = Set.of("type", "path", "variable");
    private static final LoaderOptions YAML_LOADER_OPTIONS = yamlLoaderOptions();

    private final ObjectMapper yamlMapper = new ObjectMapper(yamlFactory());
    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public CapabilityPackage parse(String document) {
        ObjectNode root = readRoot(document);
        requireOnlyFields(root, ROOT_FIELDS, "document");
        String apiVersion = text(root, "apiVersion", "apiVersion");
        if (!API_VERSION.equals(apiVersion)) {
            throw invalid("unsupported apiVersion");
        }
        String kind = text(root, "kind", "kind");
        if (!KIND.equals(kind)) {
            throw invalid("unsupported kind");
        }

        ObjectNode metadataNode = object(root, "metadata", "metadata");
        requireOnlyFields(metadataNode, METADATA_FIELDS, "metadata");
        validateLabels(metadataNode);
        Metadata metadata = new Metadata(
                text(metadataNode, "name", "metadata.name"),
                text(metadataNode, "namespace", "metadata.namespace"),
                text(metadataNode, "version", "metadata.version"));

        ObjectNode releaseNode = object(root, "release", "release");
        requireOnlyFields(releaseNode, RELEASE_FIELDS, "release");
        validateRelease(releaseNode);
        ReleaseDescriptor release = new ReleaseDescriptor(optionalText(releaseNode, "digest", "release.digest"));

        ObjectNode specNode = object(root, "spec", "spec");
        requireOnlyFields(specNode, SPEC_FIELDS, "spec");
        ArrayNode capabilityNodes = array(specNode, "capabilities", "spec.capabilities");
        if (capabilityNodes.isEmpty()) {
            throw invalid("missing required spec.capabilities");
        }

        List<CapabilityDefinition> capabilities = new ArrayList<>();
        Set<String> capabilityIds = new HashSet<>();
        for (JsonNode capabilityNode : capabilityNodes) {
            CapabilityDefinition capability = parseCapability(
                    requireObject(capabilityNode, "spec.capabilities entry"));
            if (!capabilityIds.add(capability.id())) {
                throw invalid("duplicate capability id");
            }
            capabilities.add(capability);
        }

        CanonicalForm canonical = canonicalize(root);
        return new CapabilityPackage(apiVersion, kind, metadata, release, capabilities,
                canonical.document(), canonical.digest());
    }

    private CapabilityDefinition parseCapability(ObjectNode node) {
        requireOnlyFields(node, CAPABILITY_FIELDS, "capability");
        String id = text(node, "id", "capability.id");
        String type = text(node, "type", "capability.type");
        ObjectNode dependencyGroups = object(node, "dependencies", "capability.dependencies");
        requireOnlyFields(dependencyGroups, DEPENDENCY_GROUP_FIELDS, "capability.dependencies");
        List<DependencyDefinition> dependencies = new ArrayList<>();
        addDependencies(dependencies, dependencyGroups, "capabilities", "id", null);
        addDependencies(dependencies, dependencyGroups, "skills", "name", "SKILL");

        ObjectNode networkNode = object(node, "network", "capability.network");
        NetworkDefinition network = parseNetwork(networkNode);

        List<SecretDefinition> secrets = parseSecrets(node);
        return new CapabilityDefinition(id, type, dependencies, network, secrets);
    }

    private void addDependencies(
            List<DependencyDefinition> dependencies,
            ObjectNode groups,
            String field,
            String identityField,
            String fixedType) {
        ArrayNode entries = array(groups, field, "capability.dependencies." + field);
        for (JsonNode entryNode : entries) {
            ObjectNode entry = requireObject(entryNode, "dependency");
            requireOnlyFields(entry,
                    field.equals("skills") ? SKILL_DEPENDENCY_FIELDS : CAPABILITY_DEPENDENCY_FIELDS,
                    "dependency");
            validateRequiredFlag(entry);
            String digest = optionalText(entry, "digest", "dependency.digest");
            if (digest == null || !SHA256.matcher(digest).matches()) {
                throw invalid("dependency digest must be a sha256 digest");
            }
            dependencies.add(new DependencyDefinition(
                    text(entry, identityField, "dependency." + identityField),
                    fixedType == null ? text(entry, "type", "dependency.type") : fixedType,
                    text(entry, "version", "dependency.version"),
                    digest,
                    optionalText(entry, "importPath", "dependency.importPath")));
        }
    }

    private NetworkDefinition parseNetwork(ObjectNode network) {
        requireOnlyFields(network, NETWORK_FIELDS, "network");
        JsonNode defaultDeny = network.get("defaultDeny");
        if (defaultDeny == null || !defaultDeny.isBoolean() || !defaultDeny.booleanValue()) {
            throw invalid("network.defaultDeny must be true");
        }
        JsonNode allow = network.get("allow");
        if (allow != null) {
            if (!(allow instanceof ArrayNode allowEntries)) {
                throw invalid("network.allow must be an array");
            }
            for (JsonNode allowEntryNode : allowEntries) {
                ObjectNode allowEntry = requireObject(allowEntryNode, "network.allow entry");
                requireOnlyFields(allowEntry, NETWORK_ALLOW_FIELDS, "network.allow entry");
                text(allowEntry, "name", "network.allow.name");
                text(allowEntry, "protocol", "network.allow.protocol");
                text(allowEntry, "host", "network.allow.host");
                JsonNode port = allowEntry.get("port");
                if (port == null || !port.isIntegralNumber()) {
                    throw invalid("network.allow.port must be an integer");
                }
            }
        }
        return new NetworkDefinition(true);
    }

    private List<SecretDefinition> parseSecrets(ObjectNode capability) {
        ArrayNode secretNodes = array(capability, "secrets", "capability.secrets");
        List<SecretDefinition> secrets = new ArrayList<>();
        for (JsonNode secretNode : secretNodes) {
            ObjectNode secret = requireObject(secretNode, "secret");
            if (secret.has("value")) {
                throw invalid("inline secret values are forbidden");
            }
            requireOnlyFields(secret, SECRET_FIELDS, "secret");
            ObjectNode reference = object(secret, "ref", "secret.ref");
            requireOnlyFields(reference, SECRET_REF_FIELDS, "secret.ref");
            validateSecretMount(secret);
            secrets.add(new SecretDefinition(
                    text(secret, "name", "secret.name"),
                    text(reference, "provider", "secret.ref.provider"),
                    text(reference, "key", "secret.ref.key"),
                    text(reference, "version", "secret.ref.version")));
        }
        return secrets;
    }

    private void validateSecretMount(ObjectNode secret) {
        JsonNode mountNode = secret.get("mount");
        if (mountNode == null) {
            return;
        }
        ObjectNode mount = requireObject(mountNode, "secret.mount");
        requireOnlyFields(mount, SECRET_MOUNT_FIELDS, "secret.mount");
        text(mount, "type", "secret.mount.type");
        optionalText(mount, "path", "secret.mount.path");
        optionalText(mount, "variable", "secret.mount.variable");
    }

    private void validateRequiredFlag(ObjectNode dependency) {
        JsonNode required = dependency.get("required");
        if (required != null && !required.isBoolean()) {
            throw invalid("dependency.required must be boolean");
        }
    }

    private void validateLabels(ObjectNode metadata) {
        JsonNode labelsNode = metadata.get("labels");
        if (labelsNode == null) {
            return;
        }
        ObjectNode labels = requireObject(labelsNode, "metadata.labels");
        Iterator<JsonNode> values = labels.elements();
        while (values.hasNext()) {
            if (!values.next().isTextual()) {
                throw invalid("metadata.labels values must be text");
            }
        }
    }

    private void validateRelease(ObjectNode release) {
        JsonNode artifactNode = release.get("artifact");
        if (artifactNode != null) {
            ObjectNode artifact = requireObject(artifactNode, "release.artifact");
            requireOnlyFields(artifact, ARTIFACT_FIELDS, "release.artifact");
            text(artifact, "uri", "release.artifact.uri");
            text(artifact, "mediaType", "release.artifact.mediaType");
        }
        JsonNode sourceNode = release.get("source");
        if (sourceNode != null) {
            ObjectNode source = requireObject(sourceNode, "release.source");
            requireOnlyFields(source, SOURCE_FIELDS, "release.source");
            text(source, "repository", "release.source.repository");
            text(source, "revision", "release.source.revision");
        }
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
        rejectAliases(document);
        try {
            JsonNode root = yamlMapper.readTree(document);
            ObjectNode rootObject = requireObject(root, "document");
            rejectNullNodes(rootObject);
            return rootObject;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private void rejectAliases(String document) {
        try {
            Parser parser = new ParserImpl(new StreamReader(document), YAML_LOADER_OPTIONS);
            while (parser.peekEvent() != null) {
                if (parser.getEvent() instanceof AliasEvent) {
                    throw invalid("invalid capability manifest");
                }
            }
        } catch (YAMLException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private void rejectNullNodes(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            throw invalid("invalid capability manifest");
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            rejectNullNodes(children.next());
        }
    }

    private void requireOnlyFields(ObjectNode node, Set<String> allowedFields, String path) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) {
                throw invalid("unexpected field in " + path);
            }
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
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw invalid("missing required " + path);
        }
        if (!node.isTextual()) {
            throw invalid(path + " must be text");
        }
        String value = node.textValue();
        if (value.isBlank()) {
            throw invalid("missing required " + path);
        }
        return value;
    }

    private String optionalText(ObjectNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid(path + " must be text");
        }
        return node.textValue();
    }

    private static YAMLFactory yamlFactory() {
        return YAMLFactory.builder().loaderOptions(YAML_LOADER_OPTIONS).build();
    }

    private static LoaderOptions yamlLoaderOptions() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setMaxAliasesForCollections(0);
        return loaderOptions;
    }

    private InvalidCapabilityManifestException invalid(String message) {
        return new InvalidCapabilityManifestException(message);
    }

    private record CanonicalForm(String document, String digest) {
    }
}
