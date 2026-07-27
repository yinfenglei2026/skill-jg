package com.example.governance.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CapabilityPackageParserTest {
    private static final String RELEASE_DIGEST = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String MCP_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SKILL_DIGEST = "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private final CapabilityPackageParser parser = new CapabilityPackageParser();

    @Test
    void parses_a_valid_agent_package_with_pinned_dependencies_and_default_deny_networking() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest());

        CapabilityPackage.CapabilityDefinition agent = capabilityPackage.capability("support-agent");
        assertThat(capabilityPackage.apiVersion()).isEqualTo("governance.platform.example/v1alpha1");
        assertThat(capabilityPackage.kind()).isEqualTo("CapabilityPackage");
        assertThat(capabilityPackage.metadata().version()).isEqualTo("1.4.0");
        assertThat(agent.type()).isEqualTo("Agent");
        assertThat(agent.network().defaultDeny()).isTrue();
        assertThat(agent.dependencies()).extracting(CapabilityPackage.DependencyDefinition::type)
                .containsExactlyInAnyOrder("MCP", "SKILL");
        assertThat(agent.dependencies()).extracting(CapabilityPackage.DependencyDefinition::digest)
                .containsExactlyInAnyOrder(MCP_DIGEST, SKILL_DIGEST);
        assertThat(agent.dependencies()).contains(new CapabilityPackage.DependencyDefinition(
                "customer-records", "MCP", "2.3.1", MCP_DIGEST, null));
        CapabilityPackage.DependencyDefinition skill = agent.dependencies().stream()
                .filter(dependency -> dependency.type().equals("SKILL"))
                .findFirst()
                .orElseThrow();
        assertThat(skill.id()).isEqualTo("support-policy");
        assertThat(skill.type()).isEqualTo("SKILL");
        assertThat(skill.version()).isEqualTo("3.2.0");
        assertThat(skill.digest()).isEqualTo(SKILL_DIGEST);
        assertThat(skill.importPath()).isEqualTo("skills/support-policy");
    }

    @Test
    void removes_release_digest_when_canonicalizing_and_hashing_the_document() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest());

        assertThat(capabilityPackage.canonicalDocument()).doesNotContain(RELEASE_DIGEST);
        assertThat(capabilityPackage.canonicalDigest()).matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void produces_the_same_canonical_digest_for_reordered_yaml() {
        CapabilityPackage first = parser.parse(validManifest());
        CapabilityPackage reordered = parser.parse(reorderedValidManifest());

        assertThat(reordered.canonicalDocument()).isEqualTo(first.canonicalDocument());
        assertThat(reordered.canonicalDigest()).isEqualTo(first.canonicalDigest());
    }

    @Test
    void rejects_an_unknown_api_version() {
        assertInvalid(validManifest().replace("governance.platform.example/v1alpha1", "governance.platform.example/v1"),
                "unsupported apiVersion");
    }

    @Test
    void rejects_an_unknown_kind() {
        assertInvalid(validManifest().replace("kind: CapabilityPackage", "kind: OtherPackage"), "unsupported kind");
    }

    @Test
    void accepts_semver_2_package_versions() {
        CapabilityPackage prerelease = parser.parse(
                validManifest().replace("version: 1.4.0", "version: 1.4.0-rc.1+build.7"));

        assertThat(prerelease.metadata().version()).isEqualTo("1.4.0-rc.1+build.7");
    }

    @Test
    void rejects_a_non_semver_package_version() {
        assertInvalid(validManifest().replace("version: 1.4.0", "version: latest"),
                "invalid metadata.version");
    }

    @Test
    void rejects_an_invalid_release_digest() {
        assertInvalid(validManifest().replace(RELEASE_DIGEST, "not-a-digest"),
                "invalid release.digest");
    }

    @Test
    void rejects_an_unsupported_hosted_capability_type() {
        assertInvalid(validManifest().replace("type: Agent", "type: Plugin"),
                "unsupported capability type");
    }

    @Test
    void rejects_an_unsupported_capability_dependency_type() {
        assertInvalid(validManifest().replace("type: MCP", "type: Database"),
                "unsupported dependency type");
    }

    @Test
    void rejects_networking_without_default_deny() {
        assertInvalid(validManifest().replace("defaultDeny: true", "defaultDeny: false"),
                "network.defaultDeny must be true");
    }

    @Test
    void rejects_an_agent_without_a_model_policy() {
        assertInvalid(validManifest().replace("modelPolicies:\n          - general-chat", "modelPolicies: []"),
                "Agent permissions.modelPolicies must not be empty");
    }

    @Test
    void rejects_provider_direct_model_endpoints() {
        assertInvalid(validManifest().replace("model-gateway.platform.svc.cluster.local", "api.openai.com"),
                "provider-direct model endpoints are forbidden");
    }

    @Test
    void accepts_only_phase_one_network_protocols() {
        for (String protocol : new String[] {"HTTP", "HTTPS", "TCP"}) {
            parser.parse(validManifest().replace("protocol: HTTPS", "protocol: " + protocol));
        }
    }

    @Test
    void rejects_an_unsupported_network_protocol() {
        assertInvalid(validManifest().replace("protocol: HTTPS", "protocol: FTP"),
                "unsupported network protocol");
    }

    @Test
    void rejects_a_network_port_outside_the_valid_range() {
        assertInvalid(validManifest().replace("port: 8443", "port: 70000"),
                "network.allow.port must be an integer between 1 and 65535");
    }

    @Test
    void rejects_a_resource_limit_below_its_request() {
        assertInvalid(validManifest().replace("cpu: \"1\"", "cpu: 10m"),
                "resource limit must be greater than or equal to request");
    }

    @Test
    void rejects_a_zero_resource_request() {
        assertInvalid(validManifest().replace("cpu: 250m", "cpu: \"0\""),
                "resource request must be greater than zero");
    }

    @Test
    void rejects_an_unsupported_resource_quantity() {
        assertInvalid(validManifest().replace("memory: 512Mi", "memory: 512MB"),
                "invalid resource quantity");
    }

    @Test
    void rejects_a_hosted_capability_without_every_health_probe() {
        assertInvalid(validManifest().replace(
                "        liveness:\n          httpGet: { path: /health/live, port: 8080 }\n", ""),
                "missing required health.liveness");
    }

    @Test
    void rejects_inline_secret_values_without_disclosing_them() {
        assertInvalid(validManifest().replace("name: crm-client\n          ref:",
                "name: crm-client\n          value: super-secret\n          ref:"),
                "inline secret values are forbidden");
    }

    @Test
    void rejects_arbitrary_inline_secret_fields_without_disclosing_values() {
        assertInvalid(validManifest().replace("name: crm-client\n          ref:",
                "name: crm-client\n          token: super-secret\n          ref:"),
                "unexpected field in secret");
    }

    @Test
    void rejects_duplicate_yaml_mapping_keys() {
        assertInvalid(validManifest().replace("kind: CapabilityPackage",
                "kind: CapabilityPackage\nkind: CapabilityPackage"),
                "invalid capability manifest");
    }

    @Test
    void rejects_yaml_aliases() {
        String aliased = validManifest()
                .replace("metadata:\n", "metadata: &packageMetadata\n")
                .replace("release:\n", "release:\n  source: *packageMetadata\n");

        assertInvalid(aliased, "invalid capability manifest");
    }

    @Test
    void rejects_non_text_values_for_textual_fields() {
        assertInvalid(validManifest().replace("name: support-assistant", "name: 42"),
                "metadata.name must be text");
    }

    @Test
    void rejects_unexpected_fields_in_modeled_security_structures() {
        assertInvalid(validManifest().replace("type: MCP\n            version:",
                "type: MCP\n            endpoint: https://unapproved.example\n            version:"),
                "unexpected field in dependency");
    }

    @Test
    void rejects_duplicate_capability_ids() {
        String manifest = validManifest();
        String capability = manifest.substring(manifest.indexOf("    - id: support-agent"));

        assertInvalid(manifest + capability, "duplicate capability id");
    }

    @Test
    void rejects_dependencies_without_a_sha256_digest() {
        assertInvalid(validManifest().replace(MCP_DIGEST, "sha512:0123456789abcdef"),
                "dependency digest must be a sha256 digest");
        assertInvalid(validManifest().replace("            digest: " + MCP_DIGEST + "\n", ""),
                "dependency digest must be a sha256 digest");
    }

    @Test
    void rejects_a_skill_dependency_without_a_name() {
        assertInvalid(validManifest().replace("- name: support-policy", "-"),
                "missing required dependency.name");
    }

    @Test
    void rejects_malformed_yaml() {
        assertInvalid("apiVersion: [", "invalid capability manifest");
    }

    @Test
    void rejects_missing_required_structures() {
        assertInvalid("apiVersion: governance.platform.example/v1alpha1\nkind: CapabilityPackage\n", "missing required metadata");
    }

    @Test
    void rejects_lookup_for_an_unknown_capability() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest());

        assertThatThrownBy(() -> capabilityPackage.capability("missing"))
                .isInstanceOf(InvalidCapabilityManifestException.class)
                .hasMessage("capability not found: missing");
    }

    private void assertInvalid(String manifest, String message) {
        assertThatThrownBy(() -> parser.parse(manifest))
                .isInstanceOf(InvalidCapabilityManifestException.class)
                .hasMessage(message);
    }

    private String validManifest() {
        return """
                apiVersion: governance.platform.example/v1alpha1
                kind: CapabilityPackage
                metadata:
                  name: support-assistant
                  namespace: customer-operations
                  version: 1.4.0
                release:
                  digest: %s
                spec:
                  capabilities:
                    - id: support-agent
                      type: Agent
                      permissions:
                        modelPolicies:
                          - general-chat
                      dependencies:
                        capabilities:
                          - id: customer-records
                            type: MCP
                            version: 2.3.1
                            digest: %s
                        skills:
                          - name: support-policy
                            version: 3.2.0
                            digest: %s
                            importPath: skills/support-policy
                      network:
                        defaultDeny: true
                        allow:
                          - name: model-gateway
                            protocol: HTTPS
                            host: model-gateway.platform.svc.cluster.local
                            port: 8443
                      secrets:
                        - name: crm-client
                          ref:
                            provider: platform-secret-store
                            key: customer-operations/crm-client
                            version: \"12\"
                      resources:
                        requests:
                          cpu: 250m
                          memory: 512Mi
                        limits:
                          cpu: \"1\"
                          memory: 1Gi
                      health:
                        startup:
                          httpGet: { path: /health/startup, port: 8080 }
                        readiness:
                          httpGet: { path: /health/ready, port: 8080 }
                        liveness:
                          httpGet: { path: /health/live, port: 8080 }
                """.formatted(RELEASE_DIGEST, MCP_DIGEST, SKILL_DIGEST);
    }

    private String reorderedValidManifest() {
        return """
                kind: CapabilityPackage
                spec:
                  capabilities:
                    - health:
                        liveness:
                          httpGet: { port: 8080, path: /health/live }
                        startup:
                          httpGet: { port: 8080, path: /health/startup }
                        readiness:
                          httpGet: { port: 8080, path: /health/ready }
                      resources:
                        limits:
                          memory: 1Gi
                          cpu: \"1\"
                        requests:
                          memory: 512Mi
                          cpu: 250m
                      network:
                        defaultDeny: true
                        allow:
                          - port: 8443
                            host: model-gateway.platform.svc.cluster.local
                            protocol: HTTPS
                            name: model-gateway
                      secrets:
                        - ref:
                            key: customer-operations/crm-client
                            version: \"12\"
                            provider: platform-secret-store
                          name: crm-client
                      dependencies:
                        skills:
                          - importPath: skills/support-policy
                            version: 3.2.0
                            digest: %s
                            name: support-policy
                        capabilities:
                          - digest: %s
                            version: 2.3.1
                            id: customer-records
                            type: MCP
                      permissions:
                        modelPolicies:
                          - general-chat
                      type: Agent
                      id: support-agent
                release:
                  digest: %s
                metadata:
                  version: 1.4.0
                  namespace: customer-operations
                  name: support-assistant
                apiVersion: governance.platform.example/v1alpha1
                """.formatted(SKILL_DIGEST, MCP_DIGEST, RELEASE_DIGEST);
    }
}
