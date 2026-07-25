# PoC Implementation Plan

## Delivery Definition

Phase one is complete only when an Agent and an MCP capability can move through validation, digest-bound approval, publication, GitOps deployment to the hosted runtime, invocation through declared dependencies, and auditable health/failure observation. Skill dependency import is included. CLI functionality is intentionally limited to catalog and download.

The tracks below may overlap, but their acceptance criteria are release gates. "Demonstrated" means repeatable through automated tests or a checked-in verification script, not a one-off screenshot.

## Track 1: Foundation

Deliverables:

- repository layout, Java 17/Spring Boot and Node 24/React build conventions;
- shared identifiers, API error envelope and audit event conventions;
- local Compose dependencies and root verification entry point;
- architecture decisions for identity, OCI artifacts, GitOps and Model Gateway contracts.

Acceptance criteria:

- **FND-01:** A clean checkout documents and validates Java 17, Node 24, Docker and `kubectl` prerequisites.
- **FND-02:** One root verification command runs server checks, portal checks, manifest schema checks and infrastructure static checks once those modules exist.
- **FND-03:** CI uses locked dependencies and produces reproducible build metadata without committing secrets.
- **FND-04:** Architecture, capability schema and PoC limitations are checked in and cross-reference the same release states and digest rule.
- **FND-05:** Secret-like local/Kubernetes artifacts are ignored, and CI secret scanning rejects committed credential fixtures outside explicitly synthetic test data.

## Track 2: Governance API

Deliverables:

- Spring Boot APIs for catalog, versions, validation, review, approval, publication and deployment intent;
- persistence for capabilities, immutable releases, approvals, policy evidence, deployments and append-oriented audit events;
- authorization rules for owner, reviewer, approver, operator and read-only consumer roles;
- CLI-facing catalog and authorized download endpoints.

Acceptance criteria:

- **API-01:** Agent and MCP manifests conforming to `v1alpha1` can be registered; unsupported kinds/versions and invalid manifests return stable typed errors.
- **API-02:** Dependency resolution locks Agent/MCP/Skill dependencies to digests, rejects cycles or missing dependencies, and cannot silently substitute versions.
- **API-03:** Approval creation requires a candidate digest; changing any release input creates a new digest that has no inherited approval.
- **API-04:** The state service enforces `DRAFT -> VALIDATING -> REVIEW_REQUIRED -> APPROVED -> PUBLISHED -> DEPLOYING -> DEPLOYED` and documented failure/revocation paths; invalid transitions are audited and rejected.
- **API-05:** Publication and deployment intent reject releases without a currently valid approval for the exact digest.
- **API-06:** Every governance mutation emits actor, action, subject, decision, time and digest where applicable; audit failure causes the mutation to fail closed.
- **API-07:** CLI credentials can list/search/get catalog metadata and download authorized digest-addressed artifacts, but cannot call mutation, approval, deployment or execution APIs.

## Track 3: Portal

Deliverables:

- React views for catalog, capability/version detail, validation evidence, dependency graph, review queue, approval decision, deployment and runtime health;
- role-aware navigation and controls backed by server authorization;
- consistent loading, empty, forbidden, conflict and degraded states.

Acceptance criteria:

- **WEB-01:** A user can trace a release from source revision through digest, evidence, approvals, GitOps deployment and runtime status without using Kubernetes tools.
- **WEB-02:** Review and approval screens show the full digest prominently and refresh/reject the action if the candidate changed.
- **WEB-03:** Agent details distinguish imported Skills from hosted MCP dependencies and show pinned versions/digests.
- **WEB-04:** Permission, secret-reference and network allowlist diffs are visible before approval; secret values are never rendered or returned.
- **WEB-05:** Unauthorized controls are absent, while direct unauthorized API attempts still return `403`; browser tests cover both behaviors.
- **WEB-06:** Responsive portal workflows meet keyboard navigation, focus, contrast and text-overflow checks at agreed desktop/mobile viewports.

## Track 4: Runtime

Deliverables:

- runtime controller translating approved deployment intent and runtime profiles into Kubernetes resources;
- hosted Agent and MCP execution adapters with stable health/telemetry contracts;
- Model Gateway integration and declared dependency resolution;
- isolation, default-deny networking, scoped identity and secret-reference resolution.

Acceptance criteria:

- **RUN-01:** Admission/reconciliation verifies approval for the exact artifact digest and rejects mutable-tag-only or unapproved deployments.
- **RUN-02:** Agent and MCP examples start with immutable artifacts, bounded resources, service accounts and all required health checks.
- **RUN-03:** A deployed Agent invokes its approved MCP tools and imported Skill dependency; an undeclared tool or mismatched dependency digest is denied and audited.
- **RUN-04:** Every runtime namespace starts with default-deny ingress/egress; only manifest allowlist destinations are reachable in network tests.
- **RUN-05:** Model requests succeed only through Model Gateway using a logical model policy. Direct provider egress and provider credentials in workloads are absent and tested.
- **RUN-06:** Gateway, MCP, registry and health failures produce documented typed/degraded states without unapproved fallback.
- **RUN-07:** Logs, metrics and traces correlate capability ID, release ID, digest and invocation ID while redacting configured sensitive content.

## Track 5: Infrastructure

Deliverables:

- repeatable two-node K3s PoC provisioning and teardown documentation;
- namespaces, RBAC, NetworkPolicy, registry, GitOps controller, Model Gateway and observability deployment declarations;
- environment overlays with secret references only;
- backup/restore demonstration for governance metadata and GitOps state where feasible in PoC.

Acceptance criteria:

- **INF-01:** A fresh PoC environment can be created from checked-in declarations without hand-editing generated Kubernetes resources.
- **INF-02:** GitOps reconciliation deploys an approved digest and reports drift; direct manual drift is reverted or surfaced according to policy.
- **INF-03:** Removing an allowlist rule demonstrably blocks the corresponding traffic, and an unrestricted namespace fails the platform readiness check.
- **INF-04:** Kubernetes RBAC tests prove runtime service accounts cannot read unrelated Secrets, mutate deployments or access control-plane data.
- **INF-05:** No plaintext secrets, kubeconfig or private keys exist in Git/OCI release bundles; deployment obtains values from the configured secret mechanism.
- **INF-06:** Node-loss and service-loss exercises capture expected PoC behavior and explicitly avoid an HA or production-readiness claim.

## Track 6: Verification

Deliverables:

- unit, contract, integration, browser and cluster-level test suites;
- end-to-end golden path for one Agent, one MCP and one imported Skill;
- negative security/failure scenarios and an evidence report;
- operator runbook for deploy, rollback, revoke and diagnose.

Acceptance criteria:

- **VER-01:** One automated scenario builds a release, locks dependencies, approves its digest, publishes, GitOps-deploys and invokes the Agent through its MCP dependency.
- **VER-02:** Mutating a manifest, Skill, MCP image or dependency lock after approval changes the digest and blocks deployment until re-approved.
- **VER-03:** Tests cover default network deny, unauthorized secret access, direct model-provider access, invalid state transitions and CLI mutation denial.
- **VER-04:** Failure injection covers Model Gateway unavailable, MCP unavailable, registry unavailable, GitOps drift and failing readiness; observed states match `docs/architecture.md`.
- **VER-05:** Rollback deploys a previously approved digest, and revocation prevents new deployment while preserving audit history.
- **VER-06:** Verification output records component versions, commit SHA, release digest and test results so another engineer can reproduce the run.
- **VER-07:** A release review explicitly lists every unmet production control; passing PoC verification is not labeled production ready.

## Suggested Sequence

1. Foundation contracts and skeleton builds.
2. Manifest validation, immutable release persistence and dependency locking.
3. Approval/state machine and catalog Portal.
4. OCI/GitOps pipeline and runtime controller.
5. Agent + MCP + Skill golden path through Model Gateway.
6. Security, failure and two-node disruption verification.

Each slice should include API contract tests, audit assertions and failure behavior before the next state transition is enabled.

## Explicit PoC Limitations

- **The two-node K3s topology is not highly available.** It cannot maintain a safe etcd/control-plane quorum under all single-node failures and is not evidence of production resilience.
- Single logical instances of database, registry, GitOps controller, Model Gateway or observability components may be used to demonstrate integration.
- Capacity, latency and concurrency targets are demonstration-scale; no enterprise scale claim is made.
- Secret-manager, identity-provider and model-provider integrations may use non-production tenants, but the same reference and least-privilege boundaries must be exercised.
- Backup, restore, revocation and node-loss runs demonstrate mechanics, not proven RPO/RTO.
- Production adoption additionally requires a three-or-more-member control plane across failure domains, HA data services, formal threat modeling, penetration testing, SLOs/on-call, compliance review, upgrade rehearsal and recurring disaster-recovery tests.

## Exit Decision

The PoC may be accepted when all `FND`, `API`, `WEB`, `RUN`, `INF` and `VER` criteria are either demonstrated or recorded as an explicit exception with owner and consequence, and the Agent + MCP golden path is repeatable. Acceptance authorizes the next engineering phase only; it is not a production deployment approval.
