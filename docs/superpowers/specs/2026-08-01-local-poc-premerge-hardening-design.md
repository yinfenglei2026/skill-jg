# Local PoC Pre-Merge Hardening Design

## Purpose

Close the correctness gaps found during the repository-wide review before treating `feat/local-poc-loop` as the integration baseline. This change keeps the existing local PoC scope: it hardens governance contracts and static runtime declarations without adding the Portal workflow, production GitOps, or hosted runtime integrations.

## Artifact Identity Binding

Release registration continues to accept a canonical capability manifest and a digest-addressed `artifactReference`. The parsed release descriptor retains `release.artifact.uri` instead of discarding it. Registration normalizes the manifest URI to its OCI repository identity and requires it to match the registry and repository in `artifactReference`; when the manifest URI already contains a digest, that digest must also match.

The release digest remains the canonical manifest digest. Registry verification still evaluates the fully qualified request artifact reference. A mismatch returns `INVALID_CAPABILITY_MANIFEST` before artifact verification or persistence, so audit and stored evidence cannot describe a different repository from the manifest.

## Deployment Attempts

One deployment intent remains associated with each immutable release. The row represents desired state, while repeated POST requests represent attempts against that same desired state:

- a `PUBLISHED`, `FAILED`, or `DEGRADED` release may start or restart reconciliation;
- a digest-matching `DEPLOYED`/`READY` intent is returned idempotently without another transition or audit mutation;
- a failed or drifted intent is reset to `RECONCILING`, retaining its stable ID and desired digest while updating the latest request actor and time;
- a different desired digest can never reuse an existing intent.

Database work is separated from external adapters. A transactional component starts the attempt and commits `DEPLOYING` before calling GitOps/runtime ports. Completion runs in a new business transaction. If an adapter throws, a failure transaction persists the intent as `FAILED`, moves the release to `FAILED`, writes failure audit records, and then the API preserves the typed `503` response. This makes integration failure observable and allows a later retry.

## Approval Segregation

The actor who moves a release into `REVIEW_REQUIRED` cannot approve that same release, even when its JWT contains both roles. The release aggregate enforces this invariant so callers cannot bypass it through another API path. A denied self-approval returns a stable conflict error and records a durable denial audit event.

This PoC enforces reviewer-versus-approver separation only. Broader policy concepts such as multiple reviewers, approval quorum, delegation, or policy-set versioning remain outside this hardening slice.

## Skill Dependency Locks

Skill `importPath` is part of the immutable dependency lock. It is persisted in a nullable database column because hosted Agent/MCP dependency rows do not use it. API dependency responses expose the value for Skills and omit it for non-Skill dependencies. Existing rows remain compatible through a nullable migration.

## Runtime DNS Policy

The runtime namespace remains default-deny. A dedicated egress rule permits UDP and TCP port 53 only to Kubernetes DNS pods in `kube-system`; it does not broaden application egress. A dependency-free PowerShell contract renders the Kustomize base and verifies default deny, DNS namespace/pod selection, and both DNS protocols. The root verifier calls this contract.

## Testing And Delivery

Every behavioral change starts with a failing focused test. API integration tests cover repository mismatch, self-approval, idempotent deployment, persisted failure, and successful retry. Domain tests cover intent restart invariants. Persistence/API tests cover Skill `importPath`. The network contract fails against the current manifest before the DNS rule is added.

After focused tests pass, the root verifier, `git diff --check`, and a complete change review are required. The branch is eligible for integration only when these gates pass and the current `feat/platform-poc` working changes can be preserved without overwrite.

## Out Of Scope

- Portal workflow implementation from Task 6.
- Configurable local PostgreSQL ports and `application-local.yml` from Task 7.
- Secret scanning, external-environment golden paths, and final PoC acceptance from Task 8.
- Live Harbor/Cosign, GitOps, K3s, Vault, Model Gateway, or production observability adapters.
