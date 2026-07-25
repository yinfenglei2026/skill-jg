# Capability Manifest Contract

## Contract Goals

`capability.yaml` is the source contract for a release bundle. It is versioned independently from application code through `apiVersion`. Phase one supports `Agent` and `MCP` entries in the same governance model. Skill packages are imported, pinned dependencies of an Agent; they are not independently hosted runtime types in this phase.

The example below is normative for field shape but illustrative for values. Server-side schema validation and policy validation are both required: schema answers whether a field is well formed; policy answers whether a caller may request it.

## Versioned Example

```yaml
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
  # Set by the supply chain after canonicalization and dependency locking.
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
```

## Field Semantics

| Field | Rule |
| --- | --- |
| `apiVersion` | Required schema version. Unknown major/alpha versions are rejected unless explicitly enabled. |
| `metadata.name/namespace/version` | Stable identity plus semantic package version; a published identity/version pair cannot be overwritten. |
| `release.digest` | Immutable SHA-256 identity of the canonical release bundle; calculated by the trusted supply chain. |
| `release.artifact` | OCI address and media type. Deployment resolves by digest, never by tag alone. |
| `type` | Exactly `Agent` or `MCP` in phase one. |
| `dependencies.capabilities` | Each dependency is pinned by version and digest; runtime substitution is prohibited. |
| `dependencies.skills` | Imported content pinned by version and digest. Skill code/content is included in release evaluation and locking. |
| `permissions` | Requested model policies, MCP tools and platform permissions. Empty means no permission. |
| `network.defaultDeny` | Must be `true`; every egress destination requires a declared rule and policy approval. |
| `network.allow` | DNS/service destination, protocol and port. Wildcards and raw public CIDRs require an explicit elevated policy. |
| `secrets[].ref` | Metadata-only pointer to an approved secret provider/key/version. Secret values are forbidden in the manifest. |
| `resources` | Kubernetes-compatible requests/limits. Policy enforces nonzero requests and bounded limits. |
| `health` | Startup, readiness and liveness contracts. Hosted HTTP workloads must define all three. |
| `runtimeProfile` | Policy-controlled hosted execution class and bounded runtime settings. It cannot grant permissions by itself. |

## Digest and Approval Binding

The release digest covers a deterministic canonical manifest, referenced Agent/MCP artifacts, imported Skill contents, the dependency lock, and required supply-chain evidence identifiers. The `release.digest` value itself and external approval records are excluded from the bytes being hashed to avoid recursion.

Approvals are control-plane records with at least:

```yaml
releaseId: customer-operations/support-assistant/1.4.0
digest: sha256:7e6d26f6e0f0c5f53c86c366726721ab2f6322d55f5d9cb8e947f48751fa84f1
decision: APPROVED
policySet: platform-default@2026.07
actor: user:reviewer@example.internal
decidedAt: 2026-07-25T09:30:00Z
```

Any byte-level release change, dependency update, rebuilt image, Skill import change or policy-required evidence change produces a new digest. Prior approvals remain in audit history but do not authorize the new digest. Deployment admission requires the desired digest to equal the digest on a currently valid approval.

## Validation Invariants

- Manifest and every referenced artifact must pass schema, policy, malware/license and provenance checks configured for the environment.
- `network.defaultDeny` must be true for every hosted capability.
- Agent model access must name a Model Gateway policy; provider keys and provider-direct endpoints are rejected.
- Dependency graph must be acyclic and fully digest-pinned before review.
- Secret values, inline credentials and unversioned secret references are rejected.
- Resource limits must be greater than or equal to requests and within the selected runtime class quota.
- A published version/digest mapping is immutable. Corrections create a new version and digest.
- CLI clients may catalog and download authorized digest-addressed releases only; manifest fields do not expand CLI privileges.
