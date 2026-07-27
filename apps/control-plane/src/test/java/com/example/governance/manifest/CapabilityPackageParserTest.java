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
                .containsExactlyInAnyOrder("MCP", "Skill");
        assertThat(agent.dependencies()).extracting(CapabilityPackage.DependencyDefinition::digest)
                .containsExactlyInAnyOrder(MCP_DIGEST, SKILL_DIGEST);
        assertThat(agent.dependencies()).contains(
                new CapabilityPackage.DependencyDefinition(
                        "customer-records", "MCP", "2.3.1", MCP_DIGEST, null),
                new CapabilityPackage.DependencyDefinition(
                        "support-policy", "Skill", "3.2.0", SKILL_DIGEST, "skills/support-policy"));
    }

    @Test
    void removes_release_digest_when_canonicalizing_and_hashing_the_document() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest());

        assertThat(capabilityPackage.canonicalDocument()).doesNotContain("release-digest");
        assertThat(capabilityPackage.sha256()).matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void produces_the_same_canonical_digest_for_reordered_yaml() {
        CapabilityPackage first = parser.parse(validManifest());
        CapabilityPackage reordered = parser.parse(reorderedValidManifest());

        assertThat(reordered.canonicalDocument()).isEqualTo(first.canonicalDocument());
        assertThat(reordered.sha256()).isEqualTo(first.sha256());
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
    void rejects_dependencies_without_a_sha256_digest() {
        assertInvalid(validManifest().replace(MCP_DIGEST, "sha512:0123456789abcdef"),
                "dependency digest must be a sha256 digest");
        assertInvalid(validManifest().replace("digest: " + MCP_DIGEST, "missingDigest: true"),
                "dependency digest must be a sha256 digest");
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
                          - id: support-policy
                            type: Skill
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
                            type: Skill
                            id: support-policy
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
