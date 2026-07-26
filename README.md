# AI Capability Governance Platform PoC

This proof of concept governs and hosts enterprise-internal Agent and MCP (Model Context Protocol) capabilities. It connects capability definition, dependency resolution, security review, approval, publication, deployment and runtime observation in one auditable chain while remaining vendor-neutral across model providers, Agent frameworks and MCP implementations.

This repository contains an early governance control-plane slice. It is intentionally not production ready: the OCI provenance/signature verifier, GitOps reconciler, Keycloak tenant and hosted runtime integrations are still pending.

## PoC Scope

Phase one must establish a complete governance loop for Agent and MCP capabilities:

- Manage the capability catalog, versions, review, approval, publication and deployment status through a Portal.
- Provide governance APIs, policy execution, audit and runtime orchestration through a Spring Boot control plane.
- Build immutable artifacts through a GitOps supply chain; approvals bind to an artifact digest, never a mutable tag.
- Run isolated Agent and MCP workloads in a K3s hosted runtime with default-deny networking.
- Route every model call through a Model Gateway for credential isolation, routing, quota enforcement and audit.
- Support imported Skills as versioned Agent dependencies; Skills are not independently hosted runtime units in phase one.
- Limit the phase-one CLI to catalog queries and downloads of authorized artifacts. It cannot publish, approve, deploy or execute remotely.

## Non-goals

- Do not claim production readiness, high availability, cross-region disaster recovery or zero-trust compliance certification.
- Do not build a general-purpose workflow engine, model-training platform or prompt IDE in phase one.
- Do not allow capabilities to hold model-provider credentials or bypass the Model Gateway.
- Do not approve a mutable image tag, Git branch or local file checksum as a release artifact.
- Do not support CLI upload, publication, approval, deployment or execution.

## Architecture Planes

The platform has four responsibility planes connected by an audit trail:

| Plane | Responsibility | PoC technology |
| --- | --- | --- |
| Control plane | Catalog, policy, approval, release, deployment intent, audit | Spring Boot + PostgreSQL-compatible database |
| Supply chain | Source validation, dependency lock, build, scan, sign, GitOps promotion | Git, CI, OCI registry, GitOps controller |
| Runtime plane | Hosted Agent/MCP workloads, isolation, health and telemetry | Two-node K3s PoC |
| Model Gateway | Provider-neutral model routing, credentials, quota and request audit | Gateway service behind a stable internal API |

The React Portal talks only to the control plane. Runtime workloads receive short-lived, least-privilege identities and never receive control-plane database or model-provider credentials. See [`docs/architecture.md`](docs/architecture.md) for trust boundaries and failure semantics.

## Repository Conventions

- `apps/control-plane`: Spring Boot governance API and release-state domain.
- `apps/portal`: operator Portal source and dependency-free PoC view.
- `infra/k8s`: K3s runtime, namespace and NetworkPolicy manifests.
- `docs`: architecture, capability contract and implementation plan.
- `capability.yaml`: capability package entry manifest; see [`docs/capability-schema.md`](docs/capability-schema.md).
- Secrets must not be committed. Local/generated Kubernetes secret material, kubeconfig files and local `.env` files are ignored; ordinary declarative Secret references remain reviewable.

## Local Prerequisites

- Java 17
- Node.js 24 and npm
- Docker Engine / Docker Desktop with Compose
- `kubectl` compatible with the target K3s cluster
- Git and PowerShell 7+ (Windows verification path)

K3s itself may run on Linux VMs or nodes. The two-node topology is for failure-path demonstration only and is **not highly available**.

## Verify the PoC

Run from the repository root:

```powershell
.\scripts\verify.ps1
```

The script uses the checked-in Maven Wrapper rather than a machine-specific Maven installation. Its first run needs access to Maven Central. The control plane defaults to PostgreSQL; export the variables documented in [`.env.example`](.env.example) before running it outside the test profile. The test profile uses H2 only for automated tests.

For local PostgreSQL and Keycloak dependencies, create a local `.env` from the documented variable names and run:

```powershell
docker compose up -d postgres keycloak
```

The imported `governance` realm has the five platform roles and maps the Keycloak user attribute `department` into access tokens. The realm import contains no users or credentials; local synthetic users are provisioned from environment values by the start script.

For repeatable local authentication tests, set `KEYCLOAK_TEST_USER_PASSWORD` in `.env`. The local start script idempotently provisions six synthetic `*.test` users across `customer-operations` and `billing`; it never imports real users or credentials. The `governance-portal` client uses Authorization Code with PKCE. A separate `governance-smoke` client enables Direct Access Grants only for this isolated local realm.

For a local control-plane process after replacing the `.env` placeholders, run:

```powershell
.\scripts\start-local.ps1
```

With the control plane running, verify the complete Keycloak-to-Spring JWT path without printing the token:

```powershell
.\scripts\smoke-local-auth.ps1
```

To run the browser portal against the same local services, create `apps/portal/.env` from these loopback values and start Vite:

```text
VITE_GOVERNANCE_API_BASE_URL=http://localhost:8080/api/v1
VITE_GOVERNANCE_OIDC_AUTHORITY=http://localhost:8081/realms/governance
VITE_GOVERNANCE_OIDC_CLIENT_ID=governance-portal
VITE_GOVERNANCE_OIDC_REDIRECT_URI=http://localhost:5173
```

```powershell
Set-Location apps/portal
npm ci
npm run dev -- --host localhost
```

The portal uses Authorization Code with PKCE in the browser and does not contain a direct-grant login path. The direct-grant `governance-smoke` client is reserved for the local PowerShell smoke script.

The React/Vite project definition is retained for normal Node environments. This workstation currently blocks `esbuild` postinstall execution; the root verifier therefore runs the dependency-free browser-module test instead. CI installs the locked dependency graph and runs Vitest. Do not interpret passing local checks as a production-readiness claim.

## Control Plane Security

All control-plane API routes require a JWT except Kubernetes health probes. Keycloak realm roles are mapped from `realm_access.roles`; a local token may instead provide a flat `roles` claim. Role names are normalized to uppercase, with `-` converted to `_`. Every JWT must include a `department` claim, which scopes capability and release access.

| Role | Allowed operations |
| --- | --- |
| `OWNER` | Create capabilities in its department |
| `REVIEWER` | Validate, request review and reject releases |
| `APPROVER` | Approve a review-ready release |
| `OPERATOR` | Register immutable OCI releases, publish, revoke and report deployment state |
| `READ_ONLY` | Read department-scoped audit events |

Release registration accepts only `oci://...@sha256:<64 hex>` references and derives the stored digest from that reference. The caller must be an authenticated `OPERATOR`, normally a CI service identity. Outside the test profile, startup fails if `HARBOR_REGISTRY` is unset. The control plane then sends a manifest `HEAD` request to the configured allowlisted registry and rejects the release unless `Docker-Content-Digest` exactly matches the requested digest. Harbor authentication and Cosign signature/provenance verification remain required next integrations; this repository does not yet claim that an OCI digest has a verified signature.
