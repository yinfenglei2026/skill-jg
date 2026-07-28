package com.example.governance.manifest;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

public final class CapabilityPackageParser {
    private static final String API_VERSION = "governance.platform.example/v1alpha1";
    private static final String KIND = "CapabilityPackage";
    private static final int MAX_SEMANTIC_VERSION_LENGTH = 256;
    private static final int MAX_RESOURCE_QUANTITY_LENGTH = 128;
    private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern CPU_QUANTITY = Pattern.compile(
            "((?:0|[1-9]\\d*)(?:\\.\\d+)?)(n|u|m)?");
    private static final Pattern MEMORY_QUANTITY = Pattern.compile(
            "((?:0|[1-9]\\d*)(?:\\.\\d+)?)(Ki|Mi|Gi|Ti|k|M|G|T)?");
    private static final Pattern IMMUTABLE_IMAGE_REFERENCE = Pattern.compile(
            "[^\\s@]+@sha256:[a-f0-9]{64}");
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    private static final Pattern INLINE_CREDENTIAL_VALUE = Pattern.compile(
            "(?i)^(?:bearer\\s+\\S+|sk[-_][A-Za-z0-9_-]+|gh[opsu]_[A-Za-z0-9]+|xox[baprs]-[A-Za-z0-9-]+)$");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("^(?:AKIA|ASIA)[A-Z0-9]{16}$");
    private static final Pattern URI_WITH_USERINFO = Pattern.compile(
            "(?i)^[a-z][a-z0-9+.-]*://[^/?#\\s]*@");
    private static final Set<String> CREDENTIAL_FIELD_NAMES = Set.of(
            "auth", "authorization", "apikey", "accesstoken", "refreshtoken", "authtoken",
            "bearertoken", "token", "password", "passwd", "credential", "credentials",
            "accesskey", "secretkey", "privatekey", "clientsecret", "providerkey",
            "secret", "secrets");
    private static final Set<String> HOSTED_CAPABILITY_TYPES = Set.of("Agent", "MCP");
    private static final Set<String> NETWORK_PROTOCOLS = Set.of("HTTP", "HTTPS", "TCP");
    private static final List<String> INTERNAL_DNS_SUFFIXES = List.of(".internal", ".svc.cluster.local");
    private static final Set<String> ROOT_FIELDS = Set.of("apiVersion", "kind", "metadata", "release", "spec");
    private static final Set<String> METADATA_FIELDS = Set.of("name", "namespace", "version", "labels");
    private static final Set<String> RELEASE_FIELDS = Set.of("digest", "artifact", "source");
    private static final Set<String> ARTIFACT_FIELDS = Set.of("uri", "mediaType");
    private static final Set<String> SOURCE_FIELDS = Set.of("repository", "revision");
    private static final Set<String> SPEC_FIELDS = Set.of("capabilities");
    private static final Set<String> CAPABILITY_FIELDS = Set.of(
            "id", "type", "entrypoint", "dependencies", "permissions", "network", "secrets",
            "resources", "health", "runtimeProfile");
    private static final Set<String> AGENT_ENTRYPOINT_FIELDS = Set.of("artifactPath", "command", "args");
    private static final Set<String> MCP_ENTRYPOINT_FIELDS = Set.of("image", "transport");
    private static final Set<String> MCP_TRANSPORT_FIELDS = Set.of("type", "port", "path");
    private static final Set<String> DEPENDENCY_GROUP_FIELDS = Set.of("capabilities", "skills");
    private static final Set<String> CAPABILITY_DEPENDENCY_FIELDS = Set.of(
            "id", "type", "version", "digest", "required");
    private static final Set<String> SKILL_DEPENDENCY_FIELDS = Set.of(
            "name", "version", "digest", "importPath");
    private static final Set<String> PERMISSIONS_FIELDS = Set.of(
            "serviceAccounts", "kubernetesApi", "modelPolicies", "tools");
    private static final Set<String> NETWORK_FIELDS = Set.of("defaultDeny", "allow");
    private static final Set<String> NETWORK_ALLOW_FIELDS = Set.of("name", "protocol", "host", "port");
    private static final Set<String> SECRET_FIELDS = Set.of("name", "ref", "mount");
    private static final Set<String> SECRET_REF_FIELDS = Set.of("provider", "key", "version");
    private static final Set<String> SECRET_MOUNT_FIELDS = Set.of("type", "path", "variable");
    private static final String SECRET_REFERENCE_METADATA_PATH_PREFIX =
            "spec.capabilities[].secrets[].ref.";
    private static final Set<String> RESOURCE_FIELDS = Set.of("requests", "limits");
    private static final Set<String> RESOURCE_QUANTITY_FIELDS = Set.of("cpu", "memory");
    private static final Set<String> HEALTH_FIELDS = Set.of("startup", "readiness", "liveness");
    private static final Set<String> HEALTH_PROBE_FIELDS = Set.of(
            "httpGet", "failureThreshold", "periodSeconds", "timeoutSeconds");
    private static final Set<String> HEALTH_HTTP_GET_FIELDS = Set.of("path", "port");
    private static final List<String> HEALTH_PROBE_OPTIONS = List.of(
            "failureThreshold", "periodSeconds", "timeoutSeconds");
    private static final Set<String> RUNTIME_PROFILE_FIELDS = Set.of(
            "class", "isolation", "replicas", "timeoutSeconds", "maxConcurrency",
            "terminationGracePeriodSeconds");
    private static final List<String> RUNTIME_PROFILE_INTEGER_FIELDS = List.of(
            "replicas", "timeoutSeconds", "maxConcurrency", "terminationGracePeriodSeconds");
    private static final LoaderOptions YAML_LOADER_OPTIONS = yamlLoaderOptions();

    private final ObjectMapper yamlMapper = new ObjectMapper(yamlFactory());
    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public CapabilityPackage parse(String document) {
        if (document == null || document.isBlank()) {
            throw invalid("invalid capability manifest");
        }
        ObjectNode root = readRoot(document);
        rejectInlineCredentialMaterial(root, "");
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
        String metadataVersion = semanticVersion(metadataNode, "version", "metadata.version");
        Metadata metadata = new Metadata(
                text(metadataNode, "name", "metadata.name"),
                text(metadataNode, "namespace", "metadata.namespace"),
                metadataVersion);

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
        if (!HOSTED_CAPABILITY_TYPES.contains(type)) {
            throw invalid("unsupported capability type");
        }
        validateEntrypoint(node, type);
        validateRuntimeProfile(node);
        validatePermissions(node, type);
        ObjectNode dependencyGroups = object(node, "dependencies", "capability.dependencies");
        requireOnlyFields(dependencyGroups, DEPENDENCY_GROUP_FIELDS, "capability.dependencies");
        List<DependencyDefinition> dependencies = new ArrayList<>();
        addDependencies(dependencies, dependencyGroups, "capabilities", "id", null);
        addDependencies(dependencies, dependencyGroups, "skills", "name", "SKILL");

        ObjectNode networkNode = object(node, "network", "capability.network");
        NetworkDefinition network = parseNetwork(networkNode);

        List<SecretDefinition> secrets = parseSecrets(node);
        validateResources(node);
        validateHealth(node);
        return new CapabilityDefinition(id, type, dependencies, network, secrets);
    }

    private void validateEntrypoint(ObjectNode capability, String type) {
        ObjectNode entrypoint = object(capability, "entrypoint", "capability.entrypoint");
        if (type.equals("Agent")) {
            requireOnlyFields(entrypoint, AGENT_ENTRYPOINT_FIELDS, "capability.entrypoint");
            requireNonBlankText(entrypoint, "artifactPath", "capability.entrypoint.artifactPath");
            validateTextArray(entrypoint, "command", false);
            validateTextArray(entrypoint, "args", true);
            return;
        }

        requireOnlyFields(entrypoint, MCP_ENTRYPOINT_FIELDS, "capability.entrypoint");
        String image = requireNonBlankText(entrypoint, "image", "capability.entrypoint.image");
        if (!IMMUTABLE_IMAGE_REFERENCE.matcher(image).matches()) {
            throw invalid("invalid capability.entrypoint.image");
        }
        ObjectNode transport = object(entrypoint, "transport", "capability.entrypoint.transport");
        requireOnlyFields(transport, MCP_TRANSPORT_FIELDS, "capability.entrypoint.transport");
        if (!"streamable-http".equals(requireNonBlankText(
                transport, "type", "capability.entrypoint.transport.type"))) {
            throw invalid("capability.entrypoint.transport.type must be streamable-http");
        }
        validatePort(transport, "port", "capability.entrypoint.transport.port");
        String path = requireNonBlankText(transport, "path", "capability.entrypoint.transport.path");
        if (!path.startsWith("/")) {
            throw invalid("invalid capability.entrypoint.transport.path");
        }
    }

    private void validateTextArray(ObjectNode parent, String field, boolean emptyAllowed) {
        JsonNode node = parent.get(field);
        String path = "capability.entrypoint." + field;
        if (!(node instanceof ArrayNode values) || (!emptyAllowed && values.isEmpty())) {
            throw invalid(path + (emptyAllowed
                    ? " must be an array of non-blank text"
                    : " must be a non-empty array of non-blank text"));
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw invalid(path + (emptyAllowed
                        ? " must be an array of non-blank text"
                        : " must be a non-empty array of non-blank text"));
            }
        }
    }

    private void validateRuntimeProfile(ObjectNode capability) {
        ObjectNode runtimeProfile = object(capability, "runtimeProfile", "capability.runtimeProfile");
        requireOnlyFields(runtimeProfile, RUNTIME_PROFILE_FIELDS, "capability.runtimeProfile");
        requireNonBlankText(runtimeProfile, "class", "capability.runtimeProfile.class");
        requireNonBlankText(runtimeProfile, "isolation", "capability.runtimeProfile.isolation");
        for (String field : RUNTIME_PROFILE_INTEGER_FIELDS) {
            JsonNode value = runtimeProfile.get(field);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() <= 0) {
                throw invalid("capability.runtimeProfile." + field + " must be a positive integer");
            }
        }
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
            String type = fixedType == null ? text(entry, "type", "dependency.type") : fixedType;
            if (fixedType == null && !HOSTED_CAPABILITY_TYPES.contains(type)) {
                throw invalid("unsupported dependency type");
            }
            String importPath = fixedType == null
                    ? optionalText(entry, "importPath", "dependency.importPath")
                    : skillImportPath(entry);
            dependencies.add(new DependencyDefinition(
                    text(entry, identityField, "dependency." + identityField),
                    type,
                    semanticVersion(entry, "version", "dependency.version"),
                    digest,
                    importPath));
        }
    }

    private String skillImportPath(ObjectNode dependency) {
        JsonNode importPath = dependency.get("importPath");
        if (importPath == null || importPath.isNull() || !importPath.isTextual()
                || importPath.textValue().isBlank()) {
            throw invalid("invalid dependency.importPath");
        }
        return importPath.textValue();
    }

    private void validatePermissions(ObjectNode capability, String type) {
        JsonNode permissionsNode = capability.get("permissions");
        if (permissionsNode == null) {
            if (type.equals("Agent")) {
                throw invalid("Agent permissions.modelPolicies must not be empty");
            }
            return;
        }
        if (!(permissionsNode instanceof ObjectNode permissions)) {
            throw invalid("permissions must be an object");
        }
        requireOnlyFields(permissions, PERMISSIONS_FIELDS, "permissions");

        validatePermissionEntries(permissions, "serviceAccounts");
        validatePermissionEntries(permissions, "kubernetesApi");
        ArrayNode policies = validatePermissionEntries(permissions, "modelPolicies");
        validatePermissionEntries(permissions, "tools");
        if (type.equals("Agent") && (policies == null || policies.isEmpty())) {
            throw invalid("Agent permissions.modelPolicies must not be empty");
        }
    }

    private ArrayNode validatePermissionEntries(ObjectNode permissions, String field) {
        JsonNode entriesNode = permissions.get(field);
        if (entriesNode == null) {
            return null;
        }
        if (!(entriesNode instanceof ArrayNode entries)) {
            throw invalid("permissions." + field + " must be an array");
        }
        for (JsonNode entry : entries) {
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw invalid("permissions." + field + " entries must be nonblank text");
            }
        }
        return entries;
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
                String protocol = text(allowEntry, "protocol", "network.allow.protocol");
                if (!NETWORK_PROTOCOLS.contains(protocol)) {
                    throw invalid("unsupported network protocol");
                }
                String host = text(allowEntry, "host", "network.allow.host");
                if (!isInternalDnsHost(host)) {
                    throw invalid("network.allow.host must be an internal DNS name");
                }
                JsonNode port = allowEntry.get("port");
                if (!isValidPort(port)) {
                    throw invalid("network.allow.port must be an integer between 1 and 65535");
                }
            }
        }
        return new NetworkDefinition(true);
    }

    private void validateResources(ObjectNode capability) {
        ObjectNode resources = object(capability, "resources", "capability.resources");
        requireOnlyFields(resources, RESOURCE_FIELDS, "resources");
        ObjectNode requests = object(resources, "requests", "resources.requests");
        requireOnlyFields(requests, RESOURCE_QUANTITY_FIELDS, "resources.requests");
        ObjectNode limits = object(resources, "limits", "resources.limits");
        requireOnlyFields(limits, RESOURCE_QUANTITY_FIELDS, "resources.limits");

        BigDecimal requestedCpu = cpuQuantity(requests, "cpu", "resources.requests.cpu");
        BigDecimal requestedMemory = memoryQuantity(requests, "memory", "resources.requests.memory");
        BigDecimal cpuLimit = cpuQuantity(limits, "cpu", "resources.limits.cpu");
        BigDecimal memoryLimit = memoryQuantity(limits, "memory", "resources.limits.memory");
        if (requestedCpu.signum() <= 0 || requestedMemory.signum() <= 0) {
            throw invalid("resource request must be greater than zero");
        }
        if (cpuLimit.compareTo(requestedCpu) < 0 || memoryLimit.compareTo(requestedMemory) < 0) {
            throw invalid("resource limit must be greater than or equal to request");
        }
    }

    private BigDecimal cpuQuantity(ObjectNode values, String field, String path) {
        String value = text(values, field, path);
        if (value.length() > MAX_RESOURCE_QUANTITY_LENGTH) {
            throw invalid("invalid resource quantity");
        }
        var matcher = CPU_QUANTITY.matcher(value);
        if (!matcher.matches()) {
            throw invalid("invalid resource quantity");
        }
        BigDecimal quantity = new BigDecimal(matcher.group(1));
        String suffix = matcher.group(2);
        if (suffix == null) {
            return quantity;
        }
        return switch (suffix) {
            case "n" -> quantity.movePointLeft(9);
            case "u" -> quantity.movePointLeft(6);
            case "m" -> quantity.movePointLeft(3);
            default -> throw invalid("invalid resource quantity");
        };
    }

    private BigDecimal memoryQuantity(ObjectNode values, String field, String path) {
        String value = text(values, field, path);
        if (value.length() > MAX_RESOURCE_QUANTITY_LENGTH) {
            throw invalid("invalid resource quantity");
        }
        var matcher = MEMORY_QUANTITY.matcher(value);
        if (!matcher.matches()) {
            throw invalid("invalid resource quantity");
        }
        return new BigDecimal(matcher.group(1)).multiply(memoryMultiplier(matcher.group(2)));
    }

    private BigDecimal memoryMultiplier(String unit) {
        if (unit == null) {
            return BigDecimal.ONE;
        }
        return switch (unit) {
            case "Ki" -> BigDecimal.valueOf(1024L);
            case "Mi" -> BigDecimal.valueOf(1024L).pow(2);
            case "Gi" -> BigDecimal.valueOf(1024L).pow(3);
            case "Ti" -> BigDecimal.valueOf(1024L).pow(4);
            case "k" -> BigDecimal.valueOf(1_000L);
            case "M" -> BigDecimal.valueOf(1_000_000L);
            case "G" -> BigDecimal.valueOf(1_000_000_000L);
            case "T" -> BigDecimal.valueOf(1_000_000_000_000L);
            default -> throw invalid("invalid resource quantity");
        };
    }

    private void validateHealth(ObjectNode capability) {
        ObjectNode health = object(capability, "health", "capability.health");
        requireOnlyFields(health, HEALTH_FIELDS, "health");
        for (String probeName : List.of("startup", "readiness", "liveness")) {
            ObjectNode probe = object(health, probeName, "health." + probeName);
            requireOnlyFields(probe, HEALTH_PROBE_FIELDS, "health." + probeName);
            ObjectNode httpGet = object(probe, "httpGet", "health." + probeName + ".httpGet");
            requireOnlyFields(httpGet, HEALTH_HTTP_GET_FIELDS, "health." + probeName + ".httpGet");
            text(httpGet, "path", "health." + probeName + ".httpGet.path");
            if (!isValidPort(httpGet.get("port"))) {
                throw invalid("health probe port must be an integer between 1 and 65535");
            }
            for (String option : HEALTH_PROBE_OPTIONS) {
                validatePositiveInteger(probe, option);
            }
        }
    }

    private void validatePositiveInteger(ObjectNode probe, String field) {
        JsonNode value = probe.get(field);
        if (value != null && (!value.isIntegralNumber() || value.bigIntegerValue().signum() <= 0)) {
            throw invalid("health probe " + field + " must be a positive integer");
        }
    }

    private boolean isInternalDnsHost(String host) {
        if (host.length() > 253 || !isAsciiLowercaseDnsHost(host) || !hasValidDnsLabels(host)) {
            return false;
        }
        return INTERNAL_DNS_SUFFIXES.stream()
                .anyMatch(suffix -> host.length() > suffix.length() && host.endsWith(suffix));
    }

    private boolean isAsciiLowercaseDnsHost(String host) {
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (!isAsciiLowercaseLetterOrDigit(character) && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private boolean hasValidDnsLabels(String host) {
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !isAsciiLowercaseLetterOrDigit(label.charAt(0))
                    || !isAsciiLowercaseLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char character = label.charAt(index);
                if (!isAsciiLowercaseLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isAsciiLowercaseLetterOrDigit(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
    }

    private boolean isValidPort(JsonNode port) {
        return port != null && port.isIntegralNumber() && port.canConvertToInt()
                && port.intValue() >= 1 && port.intValue() <= 65535;
    }

    private void validatePort(ObjectNode parent, String field, String path) {
        if (!isValidPort(parent.get(field))) {
            throw invalid(path + " must be an integer between 1 and 65535");
        }
    }

    private String requireNonBlankText(ObjectNode parent, String field, String path) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("invalid " + path);
        }
        return value.textValue();
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
        String digest = optionalText(release, "digest", "release.digest");
        if (digest != null && !SHA256.matcher(digest).matches()) {
            throw invalid("invalid release.digest");
        }
        ObjectNode artifact = object(release, "artifact", "release.artifact");
        requireOnlyFields(artifact, ARTIFACT_FIELDS, "release.artifact");
        String artifactUri = requireNonBlankText(artifact, "uri", "release.artifact.uri");
        if (!isValidArtifactUri(artifactUri)) {
            throw invalid("invalid release.artifact.uri");
        }
        String mediaType = requireNonBlankText(artifact, "mediaType", "release.artifact.mediaType");
        if (!MEDIA_TYPE.matcher(mediaType).matches()) {
            throw invalid("invalid release.artifact.mediaType");
        }
        JsonNode sourceNode = release.get("source");
        if (sourceNode != null) {
            ObjectNode source = requireObject(sourceNode, "release.source");
            requireOnlyFields(source, SOURCE_FIELDS, "release.source");
            text(source, "repository", "release.source.repository");
            text(source, "revision", "release.source.revision");
        }
    }

    private boolean isValidArtifactUri(String value) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!"oci".equals(uri.getScheme()) || uri.getRawAuthority() == null
                    || uri.getRawAuthority().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return false;
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                return false;
            }
            if (path.indexOf('@') >= 0 && path.indexOf('@') < path.lastIndexOf('/')) {
                return false;
            }
            String finalSegment = path.substring(path.lastIndexOf('/') + 1);
            int digestSeparator = finalSegment.indexOf('@');
            if (digestSeparator >= 0) {
                String digest = finalSegment.substring(digestSeparator + 1);
                if (!SHA256.matcher(digest).matches() || digestSeparator != finalSegment.lastIndexOf('@')) {
                    return false;
                }
                finalSegment = finalSegment.substring(0, digestSeparator);
            }
            return !finalSegment.isBlank() && !finalSegment.contains(":");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private void rejectInlineCredentialMaterial(JsonNode node, String path) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<String> fields = objectNode.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode child = objectNode.get(field);
                String childPath = path.isEmpty() ? field : path + "." + field;
                if (childPath.equals("spec.capabilities[].secrets")) {
                    rejectUriUserinfo(child);
                    rejectInlineCredentialMaterial(child, childPath);
                    continue;
                }
                if (isApprovedSecretReferenceMetadata(childPath)) {
                    continue;
                }
                if (isCredentialField(field)
                        || (field.equals("name") && child.isTextual() && isCredentialField(child.textValue()))) {
                    throw invalid("inline credential material is forbidden");
                }
                rejectInlineCredentialMaterial(child, childPath);
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (JsonNode child : arrayNode) {
                rejectInlineCredentialMaterial(child, path + "[]");
            }
            return;
        }
        if (node.isTextual() && isInlineCredentialValue(node.textValue())) {
            throw invalid("inline credential material is forbidden");
        }
    }

    private boolean isApprovedSecretReferenceMetadata(String path) {
        if (!path.startsWith(SECRET_REFERENCE_METADATA_PATH_PREFIX)) {
            return false;
        }
        String field = path.substring(SECRET_REFERENCE_METADATA_PATH_PREFIX.length());
        return SECRET_REF_FIELDS.contains(field);
    }

    private void rejectUriUserinfo(JsonNode node) {
        if (node.isTextual() && URI_WITH_USERINFO.matcher(node.textValue().trim()).find()) {
            throw invalid("inline credential material is forbidden");
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            rejectUriUserinfo(children.next());
        }
    }

    private boolean isCredentialField(String field) {
        String normalized = field.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return CREDENTIAL_FIELD_NAMES.contains(normalized)
                || normalized.endsWith("apikey")
                || normalized.endsWith("token")
                || normalized.endsWith("password")
                || normalized.endsWith("credentials")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("clientsecret")
                || normalized.endsWith("authorization");
    }

    private boolean isInlineCredentialValue(String value) {
        String normalized = value.trim();
        return INLINE_CREDENTIAL_VALUE.matcher(normalized).matches()
                || AWS_ACCESS_KEY.matcher(normalized).matches()
                || URI_WITH_USERINFO.matcher(normalized).find()
                || (normalized.startsWith("-----BEGIN ") && normalized.contains("PRIVATE KEY-----"));
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
        rejectAliasesAndMultipleDocuments(document);
        try {
            JsonNode root = yamlMapper.readTree(document);
            ObjectNode rootObject = requireObject(root, "document");
            rejectNullNodes(rootObject);
            return rootObject;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private void rejectAliasesAndMultipleDocuments(String document) {
        try {
            Parser parser = new ParserImpl(new StreamReader(document), YAML_LOADER_OPTIONS);
            int documentCount = 0;
            while (parser.peekEvent() != null) {
                var event = parser.getEvent();
                if (event instanceof AliasEvent) {
                    throw invalid("invalid capability manifest");
                }
                if (event instanceof DocumentStartEvent && ++documentCount > 1) {
                    throw invalid("invalid capability manifest");
                }
            }
            if (documentCount != 1) {
                throw invalid("invalid capability manifest");
            }
        } catch (YAMLException exception) {
            throw invalid("invalid capability manifest");
        }
    }

    private void rejectNullNodes(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            throw invalid("invalid capability manifest");
        }
        if (node instanceof ObjectNode objectNode) {
            Iterator<String> fields = objectNode.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode child = objectNode.get(field);
                if (field.equals("importPath") && child.isNull()) {
                    throw invalid("invalid dependency.importPath");
                }
                rejectNullNodes(child);
            }
            return;
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

    private String semanticVersion(ObjectNode parent, String field, String path) {
        String version = text(parent, field, path);
        if (version.length() > MAX_SEMANTIC_VERSION_LENGTH || !SEMVER.matcher(version).matches()) {
            throw invalid("invalid " + path);
        }
        return version;
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
