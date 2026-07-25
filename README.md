# AI Capability Governance Platform PoC

This proof of concept governs and hosts enterprise-internal Agent and MCP (Model Context Protocol) capabilities. It connects capability definition, dependency resolution, security review, approval, publication, deployment and runtime observation in one auditable chain while remaining vendor-neutral across model providers, Agent frameworks and MCP implementations.

This repository starts with architecture and delivery contracts. Application behavior will be added incrementally according to [`docs/implementation-plan.md`](docs/implementation-plan.md).

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

- `apps/server`: Spring Boot control plane (to be implemented).
- `apps/portal`: React governance Portal (to be implemented).
- `deploy`: K3s, GitOps and local dependency declarations (to be implemented).
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

## Verify the Foundation

Run from the repository root:

```powershell
git diff --check
$required = @('README.md', 'docs/architecture.md', 'docs/capability-schema.md', 'docs/implementation-plan.md', '.gitignore')
$required | ForEach-Object { if (-not (Test-Path $_) -or (Get-Item $_).Length -eq 0) { throw "Missing or empty: $_" } }
rg '^## (PoC Scope|Non-goals|Architecture Planes|Local Prerequisites|Verify the Foundation)$' README.md
rg '^## (Control Plane|Supply Chain|Runtime Plane|Model Gateway|Security Boundaries|Release State Machine|Failure Semantics|PoC-to-Production Topology Gap)$' docs/architecture.md
```

As code is introduced, each implementation track must add its own build and test command to the root verification entry point. No production-readiness claim may be derived from passing the document-stage checks above.
