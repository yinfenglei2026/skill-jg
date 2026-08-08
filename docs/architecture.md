# Platform Architecture

## Architecture Principles

The platform governs capability releases, not mutable source locations. Agent and MCP are first-class capability types in phase one; Skill is a versioned dependency imported into an Agent package. The hosted runtime and control plane remain vendor-neutral by expressing execution, model access and tool access through platform contracts rather than a specific Agent SDK or model provider.

```text
Developer -> Git/CI -> OCI registry -> GitOps repository -> K3s runtime
                    \-> control plane release record <- Portal
Runtime workload -> Model Gateway -> approved model provider
Runtime workload -> approved MCP/dependency endpoints only
```

## Control Plane

The Spring Boot control plane is the system of record for governance intent and audit metadata. Its responsibilities are:

- capability catalog and immutable version records for Agent and MCP;
- schema validation, dependency resolution and policy evaluation;
- review tasks, digest-bound approvals and segregation of duties;
- release state transitions and deployment intent;
- runtime inventory, health summaries and audit event ingestion;
- authorization for Portal and CLI catalog/download operations.

PostgreSQL-compatible storage keeps relational governance state. Large artifacts remain in the OCI registry or object storage and are addressed by digest. The control plane does not rebuild artifacts during approval and does not mutate GitOps resources directly outside the reconciled deployment-intent workflow.

The React Portal is a control-plane client. It does not call Kubernetes, the registry, model providers or runtime workloads directly. CLI scope is deliberately narrower: authenticated catalog reads and downloads of releases the caller is authorized to access.

## Supply Chain

The GitOps supply chain converts reviewed source into a verifiable release candidate:

1. Validate `capability.yaml` and referenced artifacts.
2. Resolve Agent, MCP and imported Skill dependencies to immutable versions/digests and emit a lock record.
3. Build the runtime artifact and software bill of materials.
4. Scan policy-relevant content, dependencies and container images.
5. Push artifacts to OCI storage and calculate the release digest over the canonical release bundle.
6. Authenticate a read-only Harbor lookup and verify the returned digest, fixed-key Cosign signature, and source-bound SLSA provenance.
7. Record review and approval against that digest and retain the three verification evidence records atomically.
8. Promote only an approved digest into the GitOps environment repository.
9. Let the GitOps controller reconcile deployment intent into K3s.

Tags are navigation aids only. A tag may move, but a release digest cannot. Rebuilding source creates a new digest and invalidates prior approvals for promotion purposes.

## Runtime Plane

The runtime plane hosts Agent and MCP workloads as isolated Kubernetes workloads. A runtime controller translates an approved release and runtime profile into Kubernetes resources. The controller must verify the desired digest and approval state before reconciliation.

Each workload receives:

- a dedicated Kubernetes service account with least-privilege RBAC;
- resource requests and limits from the approved manifest;
- readiness, liveness and startup probes;
- default-deny ingress and egress policies plus explicit allowlist rules;
- references to secrets resolved at deployment time, never secret values from Git;
- immutable image/artifact references and release identity labels;
- structured logs, metrics and trace context correlated to capability and release IDs.

Agent workloads may call approved MCP capabilities and imported Skill content defined in their dependency lock. MCP capabilities expose only their declared protocol transport and tools. Undeclared dynamic dependency installation is rejected.

## Model Gateway

All model traffic crosses a stable internal Model Gateway API. Runtime capability manifests request a logical model policy (for example `general-chat`) rather than provider credentials or an unrestricted provider endpoint.

The gateway is responsible for:

- provider-neutral request and response adaptation;
- provider credential custody and rotation;
- tenant/capability-aware routing and model allowlists;
- token, request and cost quotas;
- timeout, retry and circuit-breaker policy;
- safety policy hooks and auditable metadata with content redaction rules.

Direct egress from a capability to public model provider endpoints is denied by network policy. Gateway unavailability is a dependency failure; it must not trigger credential injection or a direct-provider fallback.

## Security Boundaries

| Boundary | Allowed flow | Enforcement |
| --- | --- | --- |
| User to Portal/control plane | Authenticated governance operations | OIDC, RBAC/ABAC, CSRF protection, audit log |
| Control plane to data stores | Governance metadata and artifact lookup | Service identity, TLS, scoped database/Harbor robot credentials, fixed Cosign key, SLSA builder allowlist |
| CI to registry/GitOps | Build output and digest promotion | Ephemeral CI identity, signing, protected branches |
| GitOps controller to runtime | Reconcile approved deployment intent | Dedicated service account and namespace-scoped RBAC |
| Runtime workload to dependencies | Only declared MCP, service and network destinations | NetworkPolicy, service identity, dependency lock |
| Runtime workload to models | Model Gateway only | Default-deny egress, gateway auth and policy |
| Runtime workload to secrets | Named references required by approved manifest | External secret integration or scoped Kubernetes Secret |

Security defaults are fail-closed: no network destination, secret, dependency, model route or Kubernetes permission exists unless declared and approved. Audit records are append-oriented and include actor, action, subject, timestamp, decision and release digest. Sensitive prompt/body content is not logged by default.

## Release State Machine

```text
DRAFT -> VALIDATING -> REVIEW_REQUIRED -> APPROVED -> PUBLISHED -> DEPLOYING -> DEPLOYED
             |               |              |             |             |
             v               v              v             v             v
          REJECTED        REJECTED       REVOKED        FAILED        DEGRADED
```

- `DRAFT`: editable metadata/source pointer; not deployable.
- `VALIDATING`: schema, dependency, build, scan and policy checks run.
- `REVIEW_REQUIRED`: immutable candidate digest exists and awaits required approvals.
- `APPROVED`: all approvals for the exact digest are valid.
- `PUBLISHED`: approved artifact is visible in the authorized catalog and eligible for promotion.
- `DEPLOYING`: GitOps intent references the approved digest and reconciliation is pending.
- `DEPLOYED`: desired digest is ready and runtime attestation matches it.
- `DEGRADED` / `FAILED`: runtime or reconciliation is unhealthy; approval remains historically auditable but does not assert runtime health.
- `REJECTED`: validation or review failed. A corrected candidate starts with a new digest.
- `REVOKED`: release is blocked from new deployment; policy determines whether active instances are removed or quarantined.

Only the control plane transition service may advance governance state. GitOps/runtime observations may drive `DEPLOYING`, `DEPLOYED`, `DEGRADED` or `FAILED`, but cannot manufacture approval. Rollback means deploying a previously approved digest; it never changes the digest attached to a release.

## Failure Semantics

- **Validation or scan unavailable:** keep the candidate out of `APPROVED`; do not treat missing evidence as success.
- **Dependency resolution failure:** reject the candidate before digest approval. Runtime must not fetch an unpinned substitute.
- **Approval service/database unavailable:** reject mutating operations and keep existing runtime state; do not promote.
- **Registry unavailable:** retry bounded reads; mark deployment failed/degraded without rebuilding or switching tags.
- **GitOps reconciliation failure:** preserve desired digest and surface drift. Manual cluster changes are not accepted as authoritative state.
- **Model Gateway timeout:** apply bounded retry/circuit breaking, return a typed dependency error and emit redacted telemetry. Never bypass the gateway.
- **MCP dependency unavailable:** Agent receives a typed tool-unavailable result according to its failure policy; undeclared fallback endpoints are forbidden.
- **Health check failure:** stop routing new traffic when readiness fails; restart only according to bounded Kubernetes policy and mark sustained failures degraded.
- **Network policy cannot be enforced:** the workload must not be scheduled into an unrestricted namespace.
- **Audit sink unavailable:** buffer within a bounded durable mechanism for runtime observations; governance mutations requiring audit fail closed.

## PoC-to-Production Topology Gap

The PoC uses a two-node K3s cluster to demonstrate scheduling, isolation, reconciliation and selected node-failure behavior. **Two-node K3s is not highly available**: quorum, control-plane survival and workload continuity cannot be guaranteed.

| Concern | PoC | Production expectation |
| --- | --- | --- |
| Kubernetes control plane | K3s across two nodes | At least three failure-domain-aware control-plane/etcd members |
| Workload capacity | Shared small node pool | Separate, autoscaled pools with disruption budgets and anti-affinity |
| Database | Single development-compatible instance | Managed or clustered HA database with tested PITR |
| Registry/GitOps | Single logical service/controller | Replicated registry and HA controllers with backup/restore |
| Model Gateway | Single logical deployment | Multi-replica, zone-aware gateway with load shedding |
| Secrets | PoC secret integration | External KMS/HSM-backed secret manager and rotation automation |
| Network | Basic NetworkPolicy validation | Enforced CNI, egress gateway, DNS policy and continuous tests |
| Observability | Basic logs/metrics/traces | Durable multi-zone telemetry, SLOs, alerting and retention controls |
| Disaster recovery | Manual demonstration | Documented RPO/RTO, off-site backups and recurring restore exercises |

Production readiness requires threat modeling, scale/performance testing, upgrade and rollback rehearsal, compliance controls, on-call operations and proven recovery. None of those claims are implied by the PoC.
