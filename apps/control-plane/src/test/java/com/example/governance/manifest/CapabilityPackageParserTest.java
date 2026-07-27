package com.example.governance.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CapabilityPackageParserTest {
    private static final String MCP_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SKILL_DIGEST = "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private final CapabilityPackageParser parser = new CapabilityPackageParser();

    @Test
    void parses_a_valid_agent_package_with_pinned_dependencies_and_default_deny_networking() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest());

        CapabilityPackage.CapabilityDefinition agent = capabilityPackage.capability("support-agent");
        assertThat(capabilityPackage.apiVersion()).isEqualTo("governance.platform.example/v1alpha1");
        assertThat(capabilityPackage.kind()).isEqualTo("CapabilityPackage");
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

        assertThat(capabilityPackage.canonicalDocument()).doesNotContain("release-digest");
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
        String duplicate = "  capabilities:\n"
                + "    - id: support-agent\n"
                + "      type: Agent\n"
                + "      dependencies:\n"
                + "        capabilities: []\n"
                + "        skills: []\n"
                + "      network:\n"
                + "        defaultDeny: true\n"
                + "      secrets: []\n"
                + "    - id: support-agent";
        String duplicated = validManifest().replace("  capabilities:\n    - id: support-agent", duplicate);

        assertInvalid(duplicated, "duplicate capability id");
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
                  digest: sha256:release-digest
                spec:
                  capabilities:
                    - id: support-agent
                      type: Agent
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
                      secrets:
                        - name: crm-client
                          ref:
                            provider: platform-secret-store
                            key: customer-operations/crm-client
                            version: \"12\"
                """.formatted(MCP_DIGEST, SKILL_DIGEST);
    }

    private String reorderedValidManifest() {
        return """
                kind: CapabilityPackage
                spec:
                  capabilities:
                    - network:
                        defaultDeny: true
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
                      type: Agent
                      id: support-agent
                release:
                  digest: sha256:release-digest
                metadata:
                  version: 1.4.0
                  namespace: customer-operations
                  name: support-assistant
                apiVersion: governance.platform.example/v1alpha1
                """.formatted(SKILL_DIGEST, MCP_DIGEST);
    }
}
