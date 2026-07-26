# Local Verifiable Governance PoC Loop

## Purpose

Close the six identified readiness gaps with a repeatable local governance loop. The loop must prove manifest validation, immutable dependency locking, digest-bound approval, deployment intent, runtime observation, Portal workflow, audit behavior, CI coverage, and reproducible local configuration without claiming that external production infrastructure is deployed.

## Scope

The local profile implements deterministic adapters for artifact trust, GitOps reconciliation, and runtime observation. These adapters are test fixtures with persisted domain effects; they are not substitutes for Harbor/Cosign, a GitOps controller, K3s, Vault, or a Model Gateway.

The production profile keeps external integrations behind the same ports and fails closed when their required configuration is absent. Documentation lists every external deployment prerequisite explicitly.

## Control Plane

### Release package validation

The release registration API accepts a `v1alpha1` capability package document. The control plane parses and canonicalizes the document, then validates:

- supported API version and package kind;
- capability identity and type;
- immutable OCI artifact references;
- dependency version and digest pinning;
- absence of dependency cycles;
- default-deny network declaration and secret references without inline values.

The stored release record contains the canonical manifest, dependency locks, verification evidence, and immutable digest. A changed manifest or dependency creates a different release digest and therefore needs a new approval.

### Artifact trust

`ArtifactTrustVerifier` is a profile-selected port. The local implementation accepts only the configured local registry and records an explicit `LOCAL_TEST_ATTESTATION` evidence item after verifying the digest-shaped OCI reference. The production implementation retains remote registry verification and is extended only when Harbor credentials and Cosign/provenance configuration are supplied.

No local attestation is presented as a production signature or provenance verification.

### Deployment intent and observation

Published releases create an immutable deployment intent keyed by release ID and digest. The intent moves through pending, reconciling, ready, failed, or drifted outcomes.

`GitOpsReconciler` and `RuntimeObserver` are ports. The local implementations deterministically reconcile an approved intent and return a digest-matching ready observation. Production implementations remain unavailable until the external GitOps and runtime environments are configured.

Only these adapters can report deployment runtime outcomes. User-facing mutation endpoints create deployment intent; they do not directly assert that a workload is deployed.

### Audit and authorization

All accepted mutations audit in their business transaction. Rejected state transitions audit with `DENY` in a separate transaction so the rejection survives the failed business transaction. Backend role and department authorization remains authoritative.

The control plane records sufficient evidence to show the release digest, manifest verification, dependency locks, intent, observation, actor, and decision in local tests.

## Portal

The Portal reads roles from the authenticated OIDC profile and exposes only actions allowed for the current role. It adds workflow actions for validation, review, approval, publication, and deployment, plus panels for verification evidence, locked dependencies, deployment intent/status, and audit history.

Every action uses the Bearer-token API client. Hidden controls are only a usability boundary; direct API authorization remains covered by server tests. Loading, forbidden, conflict, and failed states remain explicit.

## Local Configuration and CI

Compose exposes PostgreSQL with a configurable `POSTGRES_HOST_PORT`. The example environment and startup validation require the JDBC URL to target that port. The documented local profile selects deterministic local adapters.

The root verifier runs control-plane tests, local identity contracts, Portal Vitest tests, TypeScript validation, Portal production build, Kubernetes rendering, Compose rendering, realm validation, and tracked-file secret scanning. CI uses the locked Node dependency graph and executes the same Portal test and build gates.

## Acceptance Scenarios

1. A valid package is registered with a canonical manifest, dependency locks, and local artifact evidence.
2. The release is validated, reviewed, approved, published, and deployed through a local intent and digest-matching runtime observation.
3. A manifest or dependency change yields a distinct digest and cannot inherit approval.
4. Invalid manifests, mutable references, missing/recursive dependencies, unapproved deployment, invalid state transitions, and cross-department requests are rejected and audited where applicable.
5. Portal tests prove role-aware controls and API mutation behavior; CI proves tests and production build.
6. Production adapters remain explicitly blocked without their external configuration and are documented as prerequisites.

## Out Of Scope

This change does not provision or claim live Harbor/Cosign, GitOps, K3s, Vault, Model Gateway, or production observability. It also does not represent a production readiness decision.
