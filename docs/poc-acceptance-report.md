# PoC Acceptance Report

## Decision

**Overall status: PARTIAL.** The local governance control-plane baseline is repeatable for PostgreSQL migration, Keycloak JWT authentication, Portal component behavior, and static runtime declarations. A real digest-bound approval and audit persistence path was also exercised once. The full phase-one PoC is not accepted because the external supply-chain/runtime integrations and the Agent + MCP + Skill golden path are not implemented. Desktop/mobile browser evidence is also NOT RUN because the available browser was blocked by an admin policy check.

This result authorizes continued engineering only. It is not a production-readiness or deployment approval.

## Run Evidence

- Date: 2026-08-02, Asia/Shanghai
- Branch: `feat/poc-acceptance`
- Evidence commit before this report: `fc52a9ed4c1d647990802248be98cb033d3068a0`
- Local toolchain: Java 17.0.19, Node 24.15.0, npm 11.12.1, Docker Engine 29.6.2, Docker Compose 5.3.1, kubectl 1.36.1
- Components: Spring Boot 3.2.0, PostgreSQL 16.4, Keycloak 26.0.7, React 19.2.0, TypeScript 5.9.3, Vite 7.3.6, Vitest 4.1.8
- `scripts/verify.ps1`: PASS; Java 125 tests with 1 conditional PostgreSQL test skipped, Keycloak realm 2 tests, Portal 25 tests, TypeScript, production Vite build, Kubernetes network contracts, Compose config, and realm JSON
- `scripts/test-postgres-upgrade.ps1`: PASS; disposable PostgreSQL applied V1-V3, preserved the fixture through V4, accepted nullable Skill import paths, and removed its container/network/volume
- `scripts/smoke-local-auth.ps1`: PASS against real Keycloak and Spring without printing a token
- Real approval API: PASS; `acceptance-agent:1.0.0` reached `APPROVED`, the 71-character SHA-256 digest was unchanged, and the audit actor equaled the JWT `sub`
- Browser desktop/mobile: NOT RUN; external Chrome could not verify its admin-enforced policy for localhost and no in-app browser instance was available
- Hygiene: superseded clean worktree/branch `feat/task3-schema-contract-clean` removed after file/patch comparison; `stash@{0}` preserved

Status meanings: PASS is repeatably demonstrated; PARTIAL has meaningful automated evidence but does not meet the full criterion; EXCEPTION is an acknowledged unimplemented control; NOT RUN has no execution evidence.

Unless a row states otherwise, the platform engineering team owns every non-PASS item until it is assigned in the next slice.

## Acceptance Matrix

| Criterion | Status | Evidence, consequence, and next slice |
| --- | --- | --- |
| FND-01 | PARTIAL | README documents prerequisites and the root verifier exercises the installed tools, but it does not enforce Java 17 or Node 24 versions. Consequence: an unsupported version can reach later failures. Next: add explicit version gates. |
| FND-02 | PASS | One root command ran server, Portal, identity, manifest, Compose, and Kubernetes static checks. |
| FND-03 | PARTIAL | CI uses Maven/npm locks and synthetic secrets, but does not emit commit/SBOM artifact metadata. Consequence: build provenance is incomplete. Next: add immutable CI metadata and SBOM output. |
| FND-04 | PASS | Architecture, schema, release states, digest rules, and PoC limitations are checked in and cross-referenced. |
| FND-05 | PARTIAL | Secret-like local files are ignored, but CI has no dedicated secret scanner. Consequence: committed credentials rely on review. Next: add a scanner with explicit synthetic-fixture policy. |
| API-01 | PASS | Parser/API tests cover Agent/MCP `v1alpha1`, strict fields, stable typed errors, and OCI artifact contracts. |
| API-02 | PASS | Dependency locks, missing/mismatched dependencies, Skill import paths, and capability cycles are tested. |
| API-03 | PASS | Approval is bound to the canonical candidate digest and is not inherited across releases. |
| API-04 | PASS | State, failure, retry, rejection, revocation, segregation-of-duties, and denied-transition tests pass. |
| API-05 | PASS | Publication/deployment reject draft, unapproved, revoked, or digest-mismatched releases. |
| API-06 | PARTIAL | Governance mutations and domain denials persist actor/action/subject/decision/time/digest, and real PostgreSQL audit binding to JWT `sub` passed. Wrong-role requests rejected by method security are not audited. Consequence: authorization-denial history is incomplete. Next: add an authorization-denial audit filter/listener. |
| API-07 | EXCEPTION | No restricted catalog/download CLI exists. Consequence: the CLI boundary cannot be verified. Next: design read-only credentials and digest-addressed download APIs. |
| WEB-01 | PARTIAL | Portal shows catalog, state, full digest, and artifact reference, but not source, approval history, GitOps, or runtime trace. Consequence: users cannot trace the full release lifecycle. Next: add an evidence/deployment timeline after external adapters exist. |
| WEB-02 | PASS | Component tests prove the full digest is shown beside approval, success refreshes data, and 409 preserves detail with an alert. |
| WEB-03 | EXCEPTION | Dependency locks are returned by the API but not rendered in the Portal. Consequence: reviewers cannot inspect Skill vs MCP dependencies visually. Next: add a typed dependency section. |
| WEB-04 | EXCEPTION | Permission, secret-reference, and network diffs are not rendered. Consequence: approval context is incomplete. Next: add sanitized manifest-diff views. |
| WEB-05 | PARTIAL | Component tests hide unauthorized controls and API tests return 403, but the required real browser path was NOT RUN. Consequence: role-aware browser rendering remains unverified. Next: rerun desktop/mobile role checks when browser policy access is available. |
| WEB-06 | NOT RUN | No desktop/mobile viewport, keyboard, focus, or contrast execution evidence. Consequence: responsive/accessibility regressions remain possible. Next: run browser acceptance at agreed viewports. |
| RUN-01 | PARTIAL | Control-plane/local adapters enforce exact approval and digest; no live admission controller or external reconciler exists. Consequence: a real cluster has no authoritative admission enforcement. Next: implement signed GitOps admission. |
| RUN-02 | PARTIAL | Rendered examples use immutable images, resources, service accounts, probes, and gVisor; workloads were not started in K3s. Consequence: startup and health behavior are unproven. Next: execute the examples in the PoC cluster. |
| RUN-03 | EXCEPTION | No live Agent-to-MCP/Skill invocation or undeclared dependency denial exists. Consequence: the core golden path is absent. Next: implement runtime dependency resolution and audit. |
| RUN-04 | PARTIAL | Default-deny/DNS/allowlist declarations pass static checks; live network enforcement was not exercised. Consequence: CNI enforcement may differ from rendered intent. Next: add positive and negative cluster traffic tests. |
| RUN-05 | EXCEPTION | Model Gateway and direct-provider egress tests are absent. Consequence: provider credential isolation is unproven. Next: integrate the Gateway and egress denial. |
| RUN-06 | PARTIAL | Registry errors and unavailable external deployment adapters fail closed; live Gateway/MCP/health failure behavior is absent. Consequence: cross-service degraded states are unproven. Next: add typed integration failure tests. |
| RUN-07 | EXCEPTION | Production logs/metrics/traces and redaction are not integrated. Consequence: cross-plane diagnosis is unavailable. Next: define and emit correlation attributes. |
| INF-01 | PARTIAL | Kubernetes declarations render without edits, but prerequisite services and cluster provisioning are external/manual. Consequence: a fresh environment cannot be reproduced from this repository alone. Next: add reproducible environment bootstrap. |
| INF-02 | EXCEPTION | External GitOps reconciliation and drift reporting are not configured. Consequence: deployment intent cannot reach a real cluster. Next: integrate a GitOps controller/repository. |
| INF-03 | NOT RUN | Allowlist-removal and unrestricted-namespace failure were not exercised on a cluster. Consequence: network-policy readiness cannot be asserted. Next: add CNI-backed negative tests. |
| INF-04 | NOT RUN | Least-privilege service-account declarations exist, but no live RBAC denial tests ran. Consequence: runtime privilege boundaries are unproven. Next: execute `kubectl auth can-i` and workload probes. |
| INF-05 | PARTIAL | Git ignores local keys/kubeconfigs and manifests contain references only; no Git/OCI secret scan or external secret delivery ran. Consequence: bundle hygiene and secret delivery remain unverified. Next: scan release bundles and integrate the configured secret mechanism. |
| INF-06 | NOT RUN | Node/service loss was not exercised. Consequence: PoC disruption behavior is unknown. Next: run and document two-node failure drills without an HA claim. |
| VER-01 | EXCEPTION | No automated build-to-GitOps-to-Agent/MCP golden path exists. Consequence: full PoC acceptance is blocked. Next: implement it after supply-chain/runtime integration. |
| VER-02 | PARTIAL | Canonical digest and dependency mismatch tests pass, but no post-approval OCI/GitOps mutation scenario runs end to end. Consequence: external tamper resistance is unproven. Next: add signed-artifact mutation tests. |
| VER-03 | PARTIAL | Invalid state/role tests and static default-deny checks pass; secret access, direct-provider access, and CLI mutation denial are absent. Consequence: several negative security boundaries are unproven. Next: add cluster/CLI negative suites. |
| VER-04 | PARTIAL | Registry unavailability and unconfigured deployment adapters are covered; Gateway, MCP, GitOps drift, and readiness injection are absent. Consequence: most cross-service failure states are unverified. Next: add external failure scenarios. |
| VER-05 | PARTIAL | Revocation, retry, and audit-history behavior are tested; rollback through real GitOps is absent. Consequence: operational rollback safety is unproven. Next: add previous-approved-digest rollback. |
| VER-06 | PARTIAL | This report records versions, commit, digest shape, migration outcome, and test totals, but the real approval/audit-subject check is not a checked-in command and the root verifier does not emit commit/digest metadata. Consequence: another engineer cannot reproduce every evidence item from one documented sequence. Next: add a secret-safe approval smoke script and machine-readable verification summary. |
| VER-07 | PASS | Every unmet control is listed here; README and architecture explicitly reject production-readiness claims. |

## Ordered Next Work

1. Restore an approved browser-control path and complete desktop/mobile role, mutation, overflow, keyboard, focus, and contrast evidence.
2. Add CI secret scanning plus immutable build metadata/SBOM output.
3. Design and implement Harbor authentication, Cosign signature/provenance policy, trust roots, audit evidence, and negative tests.
4. Integrate external GitOps and K3s admission/reconciliation, then execute live network/RBAC/failure tests.
5. Add Model Gateway, Agent + MCP + Skill invocation, observability, rollback, and the restricted read-only CLI.
