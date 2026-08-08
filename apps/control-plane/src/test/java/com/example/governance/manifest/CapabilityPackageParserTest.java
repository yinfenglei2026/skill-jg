package com.example.governance.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CapabilityPackageParserTest {
    private static final String RELEASE_DIGEST = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String MCP_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String NORMATIVE_MCP_DIGEST =
            "sha256:0561ec4dfdf541a3f669c25e92113ef319cfb3f39b196839f198328c30e87c4e";
    private static final String SKILL_DIGEST = "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String ARTIFACT_URI = "oci://registry.example.internal/capabilities/support-assistant";

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
    void parses_the_complete_normative_manifest_and_preserves_documented_subtrees() {
        CapabilityPackage capabilityPackage = parser.parse(normativeManifest());

        assertThat(capabilityPackage.release().sourceRepository())
                .isEqualTo("https://git.example.internal/support/assistant.git");
        assertThat(capabilityPackage.release().sourceRevision())
                .isEqualTo("5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42");
        assertThat(capabilityPackage.capabilities()).hasSize(2);
        assertThat(capabilityPackage.canonicalDocument())
                .contains("\"entrypoint\"")
                .contains("\"runtimeProfile\"")
                .contains("\"tools\"")
                .contains("\"path\":\"/var/run/secrets/platform/crm-client\"")
                .contains("\"variable\":\"CRM_CREDENTIAL_FILE\"");
        assertThat(capabilityPackage.capability("support-agent").secrets()).containsExactly(
                new CapabilityPackage.SecretDefinition(
                        "crm-client", "platform-secret-store", "customer-operations/crm-client", "12"));
        assertThat(capabilityPackage.capability("customer-records").secrets()).containsExactly(
                new CapabilityPackage.SecretDefinition(
                        "crm-service-account", "platform-secret-store",
                        "customer-operations/crm-service-account", "7"));
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
    void rejects_an_adversarially_long_package_version_with_a_domain_error() {
        String version = "1.0.0-" + "a.".repeat(1_000) + "a";

        assertInvalid(validManifest().replace("version: 1.4.0", "version: " + version),
                "invalid metadata.version");
    }

    @Test
    void accepts_semver_2_dependency_versions() {
        CapabilityPackage capabilityPackage = parser.parse(validManifest()
                .replace("version: 2.3.1", "version: 2.3.1-rc.1+build.7")
                .replace("version: 3.2.0", "version: 3.2.0-beta.2+sha.abc"));

        assertThat(capabilityPackage.capability("support-agent").dependencies())
                .extracting(CapabilityPackage.DependencyDefinition::version)
                .containsExactlyInAnyOrder("2.3.1-rc.1+build.7", "3.2.0-beta.2+sha.abc");
    }

    @Test
    void rejects_an_unpinned_capability_dependency_version() {
        assertInvalid(validManifest().replace("version: 2.3.1", "version: latest"),
                "invalid dependency.version");
    }

    @Test
    void rejects_an_unpinned_skill_dependency_version() {
        assertInvalid(validManifest().replace("version: 3.2.0", "version: latest"),
                "invalid dependency.version");
    }

    @Test
    void rejects_an_invalid_release_digest() {
        assertInvalid(validManifest().replace(RELEASE_DIGEST, "not-a-digest"),
                "invalid release.digest");
    }

    @Test
    void requires_a_release_artifact() {
        assertInvalid(validManifest().replace("  artifact:\n    uri: " + ARTIFACT_URI
                        + "\n    mediaType: application/vnd.example.capability.bundle.v1+tar\n", ""),
                "missing required release.artifact");
    }

    @Test
    void requires_release_source_identity() {
        assertInvalid(validManifest().replace("  source:\n"
                        + "    repository: https://git.example.internal/support/assistant.git\n"
                        + "    revision: 5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42\n", ""),
                "missing required release.source");
    }

    @Test
    void rejects_invalid_release_source_repositories() {
        assertInvalid(validManifest().replace(
                        "    repository: https://git.example.internal/support/assistant.git\n", ""),
                "invalid release.source.repository");
        for (String repository : new String[] {
                "",
                "   ",
                "relative/path",
                "http://git.example.internal/support/assistant.git",
                "https://git.example.internal/support/assistant.git?token=secret",
                "https://git.example.internal/support/assistant.git#fragment"
        }) {
            String rendered = repository.isBlank() ? "\"" + repository + "\"" : repository;
            assertInvalid(validManifest().replace(
                            "https://git.example.internal/support/assistant.git", rendered),
                    "invalid release.source.repository");
        }
        String oversized = "https://git.example.internal/" + "a".repeat(370) + ".git";
        assertInvalid(validManifest().replace(
                        "https://git.example.internal/support/assistant.git", oversized),
                "invalid release.source.repository");

        String credentialBearing = validManifest().replace(
                "https://git.example.internal/support/assistant.git",
                "https://user:secret@git.example.internal/support/assistant.git");
        assertCredentialRejected(credentialBearing, "secret");
    }

    @Test
    void rejects_invalid_release_source_revisions() {
        assertInvalid(validManifest().replace(
                        "    revision: 5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42\n", ""),
                "invalid release.source.revision");
        for (String revision : new String[] {
                "",
                "   ",
                "5d3c2c6e",
                "5D3C2C6E816C4DD86819F57FC1D91AD30B9E3D42",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        }) {
            String rendered = revision.isBlank() ? "\"" + revision + "\"" : revision;
            assertInvalid(validManifest().replace(
                            "5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42", rendered),
                    "invalid release.source.revision");
        }
    }

    @Test
    void accepts_an_immutable_digest_addressed_release_artifact() {
        assertThatCode(() -> parser.parse(validManifest().replace(ARTIFACT_URI,
                ARTIFACT_URI + "@" + MCP_DIGEST))).doesNotThrowAnyException();
    }

    @Test
    void accepts_a_release_artifact_registry_with_an_explicit_port() {
        assertThatCode(() -> parser.parse(validManifest().replace(
                ARTIFACT_URI, "oci://registry.example.internal:5000/capabilities/support-assistant")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_malformed_or_mutable_release_artifact_uris() {
        for (String uri : new String[] {
                "https://registry.example.internal/capabilities/support-assistant",
                "OCI://registry.example.internal/capabilities/support-assistant",
                "oci:///capabilities/support-assistant",
                "oci://registry.example.internal",
                "oci://registry.example.internal/capabilities/support-assistant:latest",
                "oci://registry.example.internal/capabilities/support-assistant?tag=latest",
                "oci://registry.example.internal/capabilities/support-assistant#latest",
                "oci://registry.example.internal/capabilities/support assistant",
                ARTIFACT_URI + "@" + MCP_DIGEST + "/child",
                ARTIFACT_URI + "@sha256:ABCDEF"
        }) {
            assertInvalid(validManifest().replace(ARTIFACT_URI, uri),
                    "invalid release.artifact.uri");
        }
    }

    @Test
    void rejects_a_percent_encoded_mutable_release_artifact_tag() {
        assertInvalid(validManifest().replace(ARTIFACT_URI,
                        "oci://registry.example.internal/capabilities/support-assistant%3Alatest"),
                "invalid release.artifact.uri");
    }

    @Test
    void rejects_a_release_artifact_authority_without_a_host() {
        assertInvalid(validManifest().replace(ARTIFACT_URI,
                        "oci://:5000/capabilities/support-assistant"),
                "invalid release.artifact.uri");
    }

    @Test
    void rejects_a_release_artifact_authority_with_a_non_numeric_port() {
        assertInvalid(validManifest().replace(ARTIFACT_URI,
                        "oci://registry.example.internal:abc/capabilities/support-assistant"),
                "invalid release.artifact.uri");
    }

    @Test
    void rejects_release_artifact_userinfo_without_disclosing_it() {
        String secret = "super-secret";
        String manifest = validManifest().replace(ARTIFACT_URI,
                "oci://user:" + secret + "@registry.example.internal/capabilities/support-assistant");

        assertCredentialRejected(manifest, secret);
    }

    @Test
    void requires_a_simple_release_artifact_media_type() {
        for (String mediaType : new String[] {"", "application", "application/json; charset=utf-8", "text /plain"}) {
            String rendered = mediaType.isEmpty() ? "\"\"" : "\"" + mediaType + "\"";
            assertInvalid(validManifest().replace(
                            "application/vnd.example.capability.bundle.v1+tar", rendered),
                    "invalid release.artifact.mediaType");
        }
    }

    @Test
    void rejects_an_unsupported_hosted_capability_type() {
        assertInvalid(validManifest().replace("type: Agent", "type: Plugin"),
                "unsupported capability type");
    }

    @Test
    void requires_an_agent_entrypoint_with_the_exact_schema() {
        assertInvalid(validManifest().replace(
                        "      entrypoint:\n        artifactPath: agents/support-agent\n"
                                + "        command: [\"/opt/platform/bin/agent-runner\"]\n"
                                + "        args: [\"--manifest\", \"/workspace/agent.json\"]\n", ""),
                "missing required capability.entrypoint");
        assertInvalid(validManifest().replace("        artifactPath: agents/support-agent\n", ""),
                "invalid capability.entrypoint.artifactPath");
        assertInvalid(validManifest().replace("artifactPath: agents/support-agent", "artifactPath: \"   \""),
                "invalid capability.entrypoint.artifactPath");
        assertInvalid(validManifest().replace("artifactPath: agents/support-agent", "artifactPath: 42"),
                "invalid capability.entrypoint.artifactPath");
        assertInvalid(validManifest().replace("        command: [\"/opt/platform/bin/agent-runner\"]\n", ""),
                "capability.entrypoint.command must be a non-empty array of non-blank text");
        assertInvalid(validManifest().replace("        command: [\"/opt/platform/bin/agent-runner\"]",
                        "        command: []"),
                "capability.entrypoint.command must be a non-empty array of non-blank text");
        assertInvalid(validManifest().replace("        command: [\"/opt/platform/bin/agent-runner\"]",
                        "        command: [42]"),
                "capability.entrypoint.command must be a non-empty array of non-blank text");
        assertInvalid(validManifest().replace("        args: [\"--manifest\", \"/workspace/agent.json\"]",
                        "        args: [\"   \"]"),
                "capability.entrypoint.args must be an array of non-blank text");
        assertInvalid(validManifest().replace("        args: [\"--manifest\", \"/workspace/agent.json\"]\n", ""),
                "capability.entrypoint.args must be an array of non-blank text");
        assertInvalid(validManifest().replace("        args: [\"--manifest\", \"/workspace/agent.json\"]",
                        "        args: []\n        grantAdmin: true"),
                "unexpected field in capability.entrypoint");
    }

    @Test
    void allows_an_agent_entrypoint_with_empty_args() {
        assertThatCode(() -> parser.parse(validManifest().replace(
                "args: [\"--manifest\", \"/workspace/agent.json\"]", "args: []")))
                .doesNotThrowAnyException();
    }

    @Test
    void validates_the_mcp_entrypoint_contract() {
        assertInvalid(normativeManifest().replace(
                        "registry.example.internal/mcp/customer-records@" + NORMATIVE_MCP_DIGEST,
                        "registry.example.internal/mcp/customer-records:latest"),
                "invalid capability.entrypoint.image");
        assertInvalid(normativeManifest().replace(
                        "        image: registry.example.internal/mcp/customer-records@" + NORMATIVE_MCP_DIGEST + "\n", ""),
                "invalid capability.entrypoint.image");
        assertInvalid(normativeManifest().replace("type: streamable-http", "type: websocket"),
                "capability.entrypoint.transport.type must be streamable-http");
        assertInvalid(normativeManifest().replace("          port: 8080\n          path: /mcp",
                        "          port: 0\n          path: /mcp"),
                "capability.entrypoint.transport.port must be an integer between 1 and 65535");
        assertInvalid(normativeManifest().replace("          port: 8080\n          path: /mcp",
                        "          port: 1.5\n          path: /mcp"),
                "capability.entrypoint.transport.port must be an integer between 1 and 65535");
        assertInvalid(normativeManifest().replace("          path: /mcp", "          path: mcp"),
                "invalid capability.entrypoint.transport.path");
        assertInvalid(normativeManifest().replace("          path: /mcp", "          path: /mcp\n          grantAdmin: true"),
                "unexpected field in capability.entrypoint.transport");
    }

    @Test
    void requires_the_exact_runtime_profile_schema() {
        String runtimeProfile = "      runtimeProfile:\n"
                + "        class: hosted-standard\n"
                + "        isolation: namespace\n"
                + "        replicas: 1\n"
                + "        timeoutSeconds: 90\n"
                + "        maxConcurrency: 8\n"
                + "        terminationGracePeriodSeconds: 30\n";
        assertInvalid(validManifest().replace(runtimeProfile, ""),
                "missing required capability.runtimeProfile");
        assertInvalid(validManifest().replace(runtimeProfile, "      runtimeProfile: [grant-admin]\n"),
                "missing required capability.runtimeProfile");
        assertInvalid(validManifest().replace("      runtimeProfile:\n", "      runtimeProfile:\n        grantAdmin: true\n"),
                "unexpected field in capability.runtimeProfile");
        assertInvalid(validManifest().replace("        class: hosted-standard\n", ""),
                "invalid capability.runtimeProfile.class");
        assertInvalid(validManifest().replace("        isolation: namespace", "        isolation: [grant-admin]"),
                "invalid capability.runtimeProfile.isolation");
        assertInvalid(validManifest().replace("        replicas: 1\n", ""),
                "capability.runtimeProfile.replicas must be a positive integer");
        for (String field : new String[] {
                "replicas", "timeoutSeconds", "maxConcurrency", "terminationGracePeriodSeconds"
        }) {
            assertInvalid(validManifest().replace("        " + field + ": " + runtimeValue(field),
                            "        " + field + ": 0"),
                    "capability.runtimeProfile." + field + " must be a positive integer");
            assertInvalid(validManifest().replace("        " + field + ": " + runtimeValue(field),
                            "        " + field + ": 1.5"),
                    "capability.runtimeProfile." + field + " must be a positive integer");
            assertInvalid(validManifest().replace("        " + field + ": " + runtimeValue(field),
                            "        " + field + ": 2147483648"),
                    "capability.runtimeProfile." + field + " must be a positive integer");
        }
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
    void rejects_inline_provider_credentials_without_disclosing_them() {
        String manifest = validManifest().replace("modelPolicies:\n          - general-chat",
                "modelPolicies:\n          - general-chat\n        openaiApiKey: super-secret");

        assertCredentialRejected(manifest);
    }

    @Test
    void rejects_nested_credential_fields_without_disclosing_them() {
        String manifest = normativeManifest().replace("class: hosted-standard",
                "class: hosted-standard\n        providerCredentials:\n          apiKey: super-secret");

        assertCredentialRejected(manifest);
    }

    @Test
    void rejects_credentials_hidden_in_a_nested_non_schema_secrets_field() {
        String manifest = normativeManifest().replace("class: hosted-standard",
                "class: hosted-standard\n        nested:\n          secrets:\n            apiKey: super-secret");

        assertCredentialRejected(manifest);
    }

    @Test
    void rejects_inline_credential_values_without_disclosing_them() {
        String manifest = normativeManifest().replace("class: hosted-standard", "class: \"Bearer super-secret\"");

        assertCredentialRejected(manifest);
    }

    @Test
    void rejects_exact_credential_fields_outside_the_approved_secrets_structure() {
        for (String field : new String[] {"auth", "token", "secret"}) {
            String manifest = normativeManifest().replace("    owner: support-platform",
                    "    owner: support-platform\n    " + field + ": hunter2");

            assertCredentialRejected(manifest, "hunter2");
        }
    }

    @Test
    void accepts_benign_credential_rotation_metadata() {
        String manifest = normativeManifest().replace("    owner: support-platform",
                "    owner: support-platform\n    credential-rotation: scheduled");

        assertThatCode(() -> parser.parse(manifest)).doesNotThrowAnyException();
    }

    @Test
    void rejects_uri_userinfo_without_disclosing_it() {
        assertCredentialRejected(normativeManifest().replace(
                "oci://registry.example.internal/capabilities/support-assistant",
                "oci://user:hunter2@registry.example.internal/capabilities/support-assistant"), "hunter2");
        assertCredentialRejected(normativeManifest().replace(
                "https://git.example.internal/support/assistant.git",
                "https://user:hunter2@git.example.internal/support/assistant.git"), "hunter2");
    }

    @Test
    void rejects_uri_userinfo_inside_an_approved_secret_reference() {
        assertCredentialRejected(normativeManifest().replace(
                "key: customer-operations/crm-client",
                "key: https://user:hunter2@secrets.example.internal/key"), "hunter2");
    }

    @Test
    void rejects_inline_credential_values_inside_secret_mount_variables_without_disclosing_them() {
        String secret = "Bearer super-secret";
        String manifest = normativeManifest().replace(
                "variable: CRM_CREDENTIAL_FILE", "variable: \"" + secret + "\"");

        assertCredentialRejected(manifest, secret);
    }

    @Test
    void rejects_malformed_service_account_permissions() {
        assertInvalid(normativeManifest().replace("serviceAccounts: []", "serviceAccounts: platform-runner"),
                "permissions.serviceAccounts must be an array");
    }

    @Test
    void rejects_malformed_kubernetes_api_permission_entries() {
        assertInvalid(normativeManifest().replace("kubernetesApi: []", "kubernetesApi: [42]"),
                "permissions.kubernetesApi entries must be nonblank text");
    }

    @Test
    void rejects_malformed_model_policy_permissions() {
        assertInvalid(validManifest().replace("modelPolicies:\n          - general-chat", "modelPolicies: general-chat"),
                "permissions.modelPolicies must be an array");
    }

    @Test
    void rejects_malformed_tool_permission_entries() {
        assertInvalid(normativeManifest().replace(
                        "tools:\n          - mcp:customer-records/read_customer\n          - mcp:customer-records/list_cases",
                        "tools: [42]"),
                "permissions.tools entries must be nonblank text");
    }

    @Test
    void rejects_provider_direct_model_endpoints() {
        assertInvalid(validManifest().replace("model-gateway.platform.svc.cluster.local", "api.openai.com"),
                "network.allow.host must be an internal DNS name");
    }

    @Test
    void rejects_uppercase_internal_network_hosts() {
        assertInvalid(validManifest().replace(
                        "model-gateway.platform.svc.cluster.local",
                        "MODEL-GATEWAY.PLATFORM.SVC.CLUSTER.LOCAL"),
                "network.allow.host must be an internal DNS name");
    }

    @Test
    void rejects_mixed_case_internal_network_hosts() {
        assertInvalid(validManifest().replace(
                        "model-gateway.platform.svc.cluster.local",
                        "Model-gateway.platform.svc.cluster.local"),
                "network.allow.host must be an internal DNS name");
    }

    @Test
    void rejects_trailing_dot_internal_network_hosts() {
        assertInvalid(validManifest().replace(
                        "model-gateway.platform.svc.cluster.local",
                        "model-gateway.platform.svc.cluster.local."),
                "network.allow.host must be an internal DNS name");
    }

    @Test
    void rejects_all_non_internal_network_hosts() {
        for (String host : new String[] {
                "updates.example.com", "api.mistral.ai", "10.0.0.1", "127.0.0.1", "API.MISTRAL.AI."}) {
            assertInvalid(validManifest().replace("model-gateway.platform.svc.cluster.local", host),
                    "network.allow.host must be an internal DNS name");
        }
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
    void accepts_micro_cpu_requests_and_compares_them_to_decimal_cores() {
        assertThatCode(() -> parser.parse(withCpu("500u", "\"1\"")))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_equivalent_nano_micro_and_milli_cpu_boundaries() {
        assertThatCode(() -> parser.parse(withCpu("1000n", "1u")))
                .doesNotThrowAnyException();
        assertThatCode(() -> parser.parse(withCpu("1000u", "1m")))
                .doesNotThrowAnyException();
        assertThatCode(() -> parser.parse(withCpu("1000m", "\"1\"")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_nano_micro_and_milli_cpu_requests_just_above_their_limits() {
        assertInvalid(withCpu("1001n", "1u"),
                "resource limit must be greater than or equal to request");
        assertInvalid(withCpu("1001u", "1m"),
                "resource limit must be greater than or equal to request");
        assertInvalid(withCpu("1001m", "\"1\""),
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
        assertInvalid(withCpu("500x", "\"1\""), "invalid resource quantity");
    }

    @Test
    void rejects_an_adversarially_long_cpu_quantity_with_a_domain_error() {
        String quantity = "9".repeat(500_000) + "m";

        assertInvalid(validManifest().replace("cpu: 250m", "cpu: \"" + quantity + "\""),
                "invalid resource quantity");
    }

    @Test
    void rejects_an_adversarially_long_memory_quantity_with_a_domain_error() {
        String quantity = "9".repeat(500_000) + "Mi";

        assertInvalid(validManifest().replace("memory: 512Mi", "memory: \"" + quantity + "\""),
                "invalid resource quantity");
    }

    @Test
    void rejects_unexpected_resource_fields_at_every_modeled_level() {
        assertInvalid(validManifest().replace("      resources:\n        requests:",
                        "      resources:\n        storage: scratch\n        requests:"),
                "unexpected field in resources");
        assertInvalid(validManifest().replace("          cpu: 250m\n          memory: 512Mi",
                        "          cpu: 250m\n          storage: 1Gi\n          memory: 512Mi"),
                "unexpected field in resources.requests");
        assertInvalid(validManifest().replace("          cpu: \"1\"\n          memory: 1Gi",
                        "          cpu: \"1\"\n          storage: 1Gi\n          memory: 1Gi"),
                "unexpected field in resources.limits");
    }

    @Test
    void rejects_unexpected_health_fields_at_every_modeled_level() {
        assertInvalid(validManifest().replace("      health:\n        startup:",
                        "      health:\n        gracePeriodSeconds: 10\n        startup:"),
                "unexpected field in health");

        for (String[] probeAndPath : new String[][] {
                {"startup", "startup"}, {"readiness", "ready"}, {"liveness", "live"}}) {
            String probe = probeAndPath[0];
            String path = probeAndPath[1];
            assertInvalid(validManifest().replace("        " + probe + ":\n          httpGet:",
                            "        " + probe + ":\n          successThreshold: 1\n          httpGet:"),
                    "unexpected field in health." + probe);
            assertInvalid(validManifest().replace("path: /health/" + path + ", port: 8080",
                            "path: /health/" + path + ", port: 8080, scheme: HTTP"),
                    "unexpected field in health." + probe + ".httpGet");
        }
    }

    @Test
    void rejects_blank_and_non_text_health_probe_paths() {
        assertInvalid(validManifest().replace("path: /health/startup", "path: \"   \""),
                "missing required health.startup.httpGet.path");
        assertInvalid(validManifest().replace("path: /health/startup", "path: 42"),
                "health.startup.httpGet.path must be text");
    }

    @Test
    void accepts_health_probe_port_boundaries() {
        assertThatCode(() -> parser.parse(withProbePort("startup", 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> parser.parse(withProbePort("startup", 65535)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_health_probe_ports_outside_the_valid_range() {
        assertInvalid(withProbePort("startup", 0),
                "health probe port must be an integer between 1 and 65535");
        assertInvalid(withProbePort("startup", 65536),
                "health probe port must be an integer between 1 and 65535");
        assertInvalid(validManifest().replace("path: /health/startup, port: 8080",
                        "path: /health/startup, port: 1.5"),
                "health probe port must be an integer between 1 and 65535");
    }

    @Test
    void accepts_positive_integer_health_probe_options() {
        for (String option : new String[] {"failureThreshold", "periodSeconds", "timeoutSeconds"}) {
            assertThatCode(() -> parser.parse(withProbeOption("startup", option, "1")))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejects_non_positive_or_non_integer_health_probe_options() {
        for (String option : new String[] {"failureThreshold", "periodSeconds", "timeoutSeconds"}) {
            assertInvalid(withProbeOption("startup", option, "0"),
                    "health probe " + option + " must be a positive integer");
            assertInvalid(withProbeOption("startup", option, "1.5"),
                    "health probe " + option + " must be a positive integer");
        }
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
        String secret = "super-secret";
        String manifest = validManifest().replace("name: crm-client\n          ref:",
                "name: crm-client\n          token: " + secret + "\n          ref:");

        assertCredentialRejected(manifest, secret);
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
    void rejects_multiple_yaml_documents_without_disclosing_trailing_content() {
        String secret = "hunter2";
        String manifest = validManifest() + "---\npassword: " + secret + "\n";

        assertThatThrownBy(() -> parser.parse(manifest))
                .isInstanceOf(InvalidCapabilityManifestException.class)
                .hasMessage("invalid capability manifest")
                .hasMessageNotContaining(secret);
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
    void rejects_a_skill_dependency_without_an_import_path() {
        assertInvalid(validManifest().replace("            importPath: skills/support-policy\n", ""),
                "invalid dependency.importPath");
    }

    @Test
    void rejects_a_null_skill_dependency_import_path() {
        assertInvalid(validManifest().replace("importPath: skills/support-policy", "importPath:"),
                "invalid dependency.importPath");
    }

    @Test
    void rejects_a_non_text_skill_dependency_import_path() {
        assertInvalid(validManifest().replace("importPath: skills/support-policy", "importPath: 42"),
                "invalid dependency.importPath");
    }

    @Test
    void rejects_a_blank_skill_dependency_import_path() {
        assertInvalid(validManifest().replace("importPath: skills/support-policy", "importPath: \"   \""),
                "invalid dependency.importPath");
    }

    @Test
    void rejects_malformed_yaml() {
        assertInvalid("apiVersion: [", "invalid capability manifest");
    }

    @Test
    void rejects_null_and_blank_input_with_a_stable_error() {
        assertInvalid(null, "invalid capability manifest");
        assertInvalid(" \n\t", "invalid capability manifest");
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

    private void assertCredentialRejected(String manifest) {
        assertCredentialRejected(manifest, "super-secret");
    }

    private void assertCredentialRejected(String manifest, String secret) {
        assertThatThrownBy(() -> parser.parse(manifest))
                .isInstanceOf(InvalidCapabilityManifestException.class)
                .hasMessage("inline credential material is forbidden")
                .hasMessageNotContaining(secret);
    }

    private String withCpu(String request, String limit) {
        return validManifest()
                .replace("cpu: 250m", "cpu: " + request)
                .replace("cpu: \"1\"", "cpu: " + limit);
    }

    private String withProbePort(String probe, int port) {
        return validManifest().replace("path: /health/" + probe + ", port: 8080",
                "path: /health/" + probe + ", port: " + port);
    }

    private String withProbeOption(String probe, String option, String value) {
        return validManifest().replace("          httpGet: { path: /health/" + probe
                        + ", port: 8080 }",
                "          httpGet: { path: /health/" + probe + ", port: 8080 }\n"
                        + "          " + option + ": " + value);
    }

    private String runtimeValue(String field) {
        return switch (field) {
            case "replicas" -> "1";
            case "timeoutSeconds" -> "90";
            case "maxConcurrency" -> "8";
            case "terminationGracePeriodSeconds" -> "30";
            default -> throw new IllegalArgumentException("unknown runtime field");
        };
    }

    private String normativeManifest() {
        return """
                apiVersion: governance.platform.example/v1alpha1
                kind: CapabilityPackage
                metadata:
                  name: support-assistant
                  namespace: customer-operations
                  version: 1.4.0
                  labels:
                    data-classification: internal
                    owner: support-platform
                release:
                  digest: sha256:7e6d26f6e0f0c5f53c86c366726721ab2f6322d55f5d9cb8e947f48751fa84f1
                  artifact:
                    uri: oci://registry.example.internal/capabilities/support-assistant
                    mediaType: application/vnd.example.capability.bundle.v1+tar
                  source:
                    repository: https://git.example.internal/support/assistant.git
                    revision: 5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42
                spec:
                  capabilities:
                    - id: support-agent
                      type: Agent
                      entrypoint:
                        artifactPath: agents/support-agent
                        command: ["/opt/platform/bin/agent-runner"]
                        args: ["--manifest", "/workspace/agent.json"]
                      dependencies:
                        capabilities:
                          - id: customer-records
                            type: MCP
                            version: 2.3.1
                            digest: sha256:0561ec4dfdf541a3f669c25e92113ef319cfb3f39b196839f198328c30e87c4e
                            required: true
                        skills:
                          - name: support-policy
                            version: 3.2.0
                            digest: sha256:943773023148bcc19ac6d71b793863f0750f30921efdb7ccad1cf7d54d040f4b
                            importPath: skills/support-policy
                      permissions:
                        serviceAccounts: []
                        kubernetesApi: []
                        modelPolicies:
                          - general-chat
                        tools:
                          - mcp:customer-records/read_customer
                          - mcp:customer-records/list_cases
                      network:
                        defaultDeny: true
                        allow:
                          - name: model-gateway
                            protocol: HTTPS
                            host: model-gateway.platform.svc.cluster.local
                            port: 8443
                          - name: customer-records-mcp
                            protocol: HTTP
                            host: customer-records.customer-operations.svc.cluster.local
                            port: 8080
                      secrets:
                        - name: crm-client
                          ref:
                            provider: platform-secret-store
                            key: customer-operations/crm-client
                            version: "12"
                          mount:
                            type: file
                            path: /var/run/secrets/platform/crm-client
                      resources:
                        requests:
                          cpu: 250m
                          memory: 512Mi
                        limits:
                          cpu: "1"
                          memory: 1Gi
                      health:
                        startup:
                          httpGet: { path: /health/startup, port: 8080 }
                          failureThreshold: 30
                          periodSeconds: 2
                        readiness:
                          httpGet: { path: /health/ready, port: 8080 }
                          periodSeconds: 10
                          timeoutSeconds: 2
                        liveness:
                          httpGet: { path: /health/live, port: 8080 }
                          periodSeconds: 20
                          timeoutSeconds: 2
                      runtimeProfile:
                        class: hosted-standard
                        isolation: namespace
                        replicas: 1
                        timeoutSeconds: 90
                        maxConcurrency: 8
                        terminationGracePeriodSeconds: 30
                    - id: customer-records
                      type: MCP
                      entrypoint:
                        image: registry.example.internal/mcp/customer-records@sha256:0561ec4dfdf541a3f669c25e92113ef319cfb3f39b196839f198328c30e87c4e
                        transport:
                          type: streamable-http
                          port: 8080
                          path: /mcp
                      dependencies:
                        capabilities: []
                        skills: []
                      permissions:
                        serviceAccounts: []
                        kubernetesApi: []
                        modelPolicies: []
                        tools: []
                      network:
                        defaultDeny: true
                        allow:
                          - name: crm-api
                            protocol: HTTPS
                            host: crm-api.example.internal
                            port: 443
                      secrets:
                        - name: crm-service-account
                          ref:
                            provider: platform-secret-store
                            key: customer-operations/crm-service-account
                            version: "7"
                          mount:
                            type: env
                            variable: CRM_CREDENTIAL_FILE
                      resources:
                        requests: { cpu: 100m, memory: 256Mi }
                        limits: { cpu: 500m, memory: 512Mi }
                      health:
                        startup:
                          httpGet: { path: /health/startup, port: 8080 }
                          failureThreshold: 20
                          periodSeconds: 3
                        readiness:
                          httpGet: { path: /health/ready, port: 8080 }
                          periodSeconds: 10
                          timeoutSeconds: 2
                        liveness:
                          httpGet: { path: /health/live, port: 8080 }
                          periodSeconds: 20
                          timeoutSeconds: 2
                      runtimeProfile:
                        class: hosted-standard
                        isolation: namespace
                        replicas: 1
                        timeoutSeconds: 30
                        maxConcurrency: 32
                        terminationGracePeriodSeconds: 20
                """;
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
                  artifact:
                    uri: %s
                    mediaType: application/vnd.example.capability.bundle.v1+tar
                  source:
                    repository: https://git.example.internal/support/assistant.git
                    revision: 5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42
                spec:
                  capabilities:
                    - id: support-agent
                      type: Agent
                      entrypoint:
                        artifactPath: agents/support-agent
                        command: ["/opt/platform/bin/agent-runner"]
                        args: ["--manifest", "/workspace/agent.json"]
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
                      runtimeProfile:
                        class: hosted-standard
                        isolation: namespace
                        replicas: 1
                        timeoutSeconds: 90
                        maxConcurrency: 8
                        terminationGracePeriodSeconds: 30
                """.formatted(RELEASE_DIGEST, ARTIFACT_URI, MCP_DIGEST, SKILL_DIGEST);
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
                      runtimeProfile:
                        terminationGracePeriodSeconds: 30
                        maxConcurrency: 8
                        timeoutSeconds: 90
                        replicas: 1
                        isolation: namespace
                        class: hosted-standard
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
                      entrypoint:
                        args: ["--manifest", "/workspace/agent.json"]
                        command: ["/opt/platform/bin/agent-runner"]
                        artifactPath: agents/support-agent
                      type: Agent
                      id: support-agent
                release:
                  artifact:
                    mediaType: application/vnd.example.capability.bundle.v1+tar
                    uri: %s
                  digest: %s
                  source:
                    revision: 5d3c2c6e816c4dd86819f57fc1d91ad30b9e3d42
                    repository: https://git.example.internal/support/assistant.git
                metadata:
                  version: 1.4.0
                  namespace: customer-operations
                  name: support-assistant
                apiVersion: governance.platform.example/v1alpha1
                """.formatted(SKILL_DIGEST, MCP_DIGEST, ARTIFACT_URI, RELEASE_DIGEST);
    }
}
